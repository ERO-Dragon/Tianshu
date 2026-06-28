package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.AXLlmPrimitiveClient;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AXMemoryRetriever {
    private final AXMemorySystem memorySystem;
    private final AXLlmPrimitiveClient primitiveClient;
    private final AXMemoryRetrievalPolicy retrievalPolicy;

    public AXMemoryRetriever(AXMemorySystem memorySystem, AXLlmPrimitiveClient primitiveClient) {
        this(memorySystem, primitiveClient, AXMemoryRetrievalPolicy.DEFAULT);
    }

    public AXMemoryRetriever(AXMemorySystem memorySystem, AXLlmPrimitiveClient primitiveClient, AXMemoryRetrievalPolicy retrievalPolicy) {
        this.memorySystem = Objects.requireNonNull(memorySystem, "memorySystem");
        this.primitiveClient = Objects.requireNonNull(primitiveClient, "primitiveClient");
        this.retrievalPolicy = retrievalPolicy == null ? AXMemoryRetrievalPolicy.DEFAULT : retrievalPolicy;
    }

    public void retrieve(AXMemoryRetrievalRequest request, Completion completion) {
        Objects.requireNonNull(completion, "completion");
        Completion safeCompletion = once(completion);
        if (request == null || !request.scope().writable() || request.queryText().isBlank() || request.maxBlocks() <= 0) {
            safeCompletion.complete(AXMemoryRetrievalResult.empty());
            return;
        }
        try {
            primitiveClient.requestStatus(request.queryText() + ".memory.status", status -> {
                try {
                    if (!statusCompleted(status) || !embeddingUsable(status.runtimeSnapshot())) {
                        safeCompletion.complete(AXMemoryRetrievalResult.empty());
                        return;
                    }
                    String namespace = status.runtimeSnapshot().embeddingNamespace();
                    primitiveClient.requestEmbedding(request.queryText() + ".memory.embed", List.of(request.queryText()), embedding -> {
                        try {
                            if (!statusCompleted(embedding) || embedding.embedResults().isEmpty()) {
                                safeCompletion.complete(AXMemoryRetrievalResult.empty());
                                return;
                            }
                            float[] queryVector = embedding.embedResults().get(0).vector();
                            if (queryVector == null || queryVector.length == 0) {
                                safeCompletion.complete(AXMemoryRetrievalResult.empty());
                                return;
                            }
                            safeCompletion.complete(search(request, namespace, queryVector));
                        } catch (RuntimeException exception) {
                            safeCompletion.complete(AXMemoryRetrievalResult.empty());
                        }
                    });
                } catch (RuntimeException exception) {
                    safeCompletion.complete(AXMemoryRetrievalResult.empty());
                }
            });
        } catch (RuntimeException exception) {
            safeCompletion.complete(AXMemoryRetrievalResult.empty());
        }
    }

    public CompletableFuture<AXMemoryRetrievalResult> retrieveAsync(AXMemoryRetrievalRequest request) {
        CompletableFuture<AXMemoryRetrievalResult> future = new CompletableFuture<>();
        retrieve(request, future::complete);
        return future;
    }

    private AXMemoryRetrievalResult search(AXMemoryRetrievalRequest request, String embeddingNamespace, float[] queryVector) {
        if (embeddingNamespace == null || embeddingNamespace.isBlank()) {
            return AXMemoryRetrievalResult.empty();
        }
        AXMemoryRetrievalIndex index = memorySystem.retrievalIndex(request.scope(), embeddingNamespace);
        if (index.isEmpty()) {
            return AXMemoryRetrievalResult.empty();
        }
        AXMemoryRetrievalIndex.RoutedCandidates routed = index.route(request.queryText(), queryVector);
        long nowMillis = System.currentTimeMillis();
        Map<String, StmContribution> contributions = new HashMap<>();
        for (AXMemoryRetrievalIndex.EventVectorEntry entry : routed.entries()) {
            AXMemoryEvent event = entry.event();
            if (event == null || event.stmId().isBlank()) {
                continue;
            }
            double relevance = index.score(entry, queryVector, routed.analysis(), nowMillis);
            if (relevance <= 0.0D) {
                continue;
            }
            contributions.computeIfAbsent(event.stmId(), StmContribution::new)
                    .add(event, index.effectiveMappingId(event.id()), relevance);
        }
        if (contributions.isEmpty()) {
            return AXMemoryRetrievalResult.empty();
        }
        List<AXStmBlock> allBlocks = memorySystem.stmBlocks().loadAll(request.scope());
        Map<String, AXStmBlock> blocksById = new LinkedHashMap<>();
        Map<String, Integer> blockOrder = new HashMap<>();
        int order = 0;
        for (AXStmBlock block : allBlocks) {
            blocksById.putIfAbsent(block.id(), block);
            blockOrder.putIfAbsent(block.id(), order++);
        }
        List<SelectedBlock> selected = selectBlocks(request, contributions, blocksById, blockOrder, allBlocks);
        List<AXStmBlock> timeline = selected.stream()
                .sorted(Comparator.comparingInt(SelectedBlock::order))
                .map(SelectedBlock::block)
                .toList();
        Set<String> selectedStmIds = timeline.stream()
                .map(AXStmBlock::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        List<AXMemoryRetrievalTrace> traces = selectedStmIds.stream()
                .map(contributions::get)
                .filter(Objects::nonNull)
                .map(StmContribution::toTrace)
                .toList();
        return new AXMemoryRetrievalResult(memorySystem.memoryBlockViews(request.scope(), timeline), traces);
    }

    private List<SelectedBlock> selectBlocks(
            AXMemoryRetrievalRequest request,
            Map<String, StmContribution> contributions,
            Map<String, AXStmBlock> blocksById,
            Map<String, Integer> blockOrder,
            List<AXStmBlock> allBlocks
    ) {
        List<SelectedBlock> selected = new ArrayList<>();
        int tokens = 0;
        Set<String> seen = new HashSet<>();
        List<StmContribution> ordered = contributions.values().stream()
                .sorted(Comparator.comparingDouble(StmContribution::score).reversed())
                .toList();
        double topScore = ordered.isEmpty() ? 0.0D : ordered.get(0).score();
        for (StmContribution contribution : ordered) {
            if (selected.size() >= request.maxBlocks()) {
                break;
            }
            AXStmBlock block = blocksById.get(contribution.stmId());
            if (block == null || block.isEmpty()) {
                continue;
            }
            List<AXStmBlock> candidates = chainExpansionCandidates(block, contribution, topScore, blockOrder, allBlocks);
            for (AXStmBlock candidate : candidates) {
                if (selected.size() >= request.maxBlocks()) {
                    break;
                }
                if (candidate == null || candidate.isEmpty() || !seen.add(candidate.id())) {
                    continue;
                }
                if (!selected.isEmpty() && request.tokenBudget() > 0 && tokens + candidate.estimatedTokens() > request.tokenBudget()) {
                    seen.remove(candidate.id());
                    continue;
                }
                selected.add(new SelectedBlock(candidate, blockOrder.getOrDefault(candidate.id(), Integer.MAX_VALUE)));
                tokens += candidate.estimatedTokens();
            }
        }
        return selected;
    }

    private List<AXStmBlock> chainExpansionCandidates(
            AXStmBlock center,
            StmContribution contribution,
            double topScore,
            Map<String, Integer> blockOrder,
            List<AXStmBlock> allBlocks
    ) {
        int radius = retrievalPolicy.chainExpansionRadius();
        if (radius <= 0 || !shouldExpandChain(contribution, topScore)) {
            return List.of(center);
        }
        Integer centerOrder = blockOrder.get(center.id());
        if (centerOrder == null || centerOrder < 0 || centerOrder >= allBlocks.size()) {
            return List.of(center);
        }
        int from = Math.max(0, centerOrder - radius);
        int to = Math.min(allBlocks.size() - 1, centerOrder + radius);
        List<AXStmBlock> candidates = new ArrayList<>();
        for (int index = from; index <= to; index++) {
            AXStmBlock candidate = allBlocks.get(index);
            if (sameChain(center, candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates.isEmpty() ? List.of(center) : candidates;
    }

    private boolean shouldExpandChain(StmContribution contribution, double topScore) {
        if (contribution == null || topScore <= 0.0D) {
            return false;
        }
        if (contribution.score() < retrievalPolicy.chainExpansionMinScore()) {
            return false;
        }
        return contribution.score() >= topScore * retrievalPolicy.chainExpansionScoreRatio();
    }

    private boolean sameChain(AXStmBlock center, AXStmBlock candidate) {
        if (center == null || candidate == null) {
            return false;
        }
        if (center.id().equals(candidate.id())) {
            return true;
        }
        return center.id().equals(candidate.previousStmId())
                || center.id().equals(candidate.nextStmId())
                || candidate.id().equals(center.previousStmId())
                || candidate.id().equals(center.nextStmId());
    }

    private boolean statusCompleted(LLMPrimitiveResultPayload result) {
        return result != null && LLMPrimitiveResultPayload.STATUS_COMPLETED.equals(result.status());
    }

    private boolean embeddingUsable(LLMRuntimeSnapshotPayload snapshot) {
        return snapshot != null && snapshot.embeddingAvailable() && !snapshot.embeddingNamespace().isBlank();
    }

    public interface Completion {
        void complete(AXMemoryRetrievalResult result);
    }

    private Completion once(Completion completion) {
        AtomicBoolean completed = new AtomicBoolean(false);
        return result -> {
            if (completed.compareAndSet(false, true)) {
                completion.complete(result == null ? AXMemoryRetrievalResult.empty() : result);
            }
        };
    }

    private record SelectedBlock(AXStmBlock block, int order) {
    }

    private static final class StmContribution {
        private final String stmId;
        private double max;
        private double sum;
        private final Set<String> effectiveMappings = new HashSet<>();
        private final List<AXMemoryRetrievalTrace.EventHit> hits = new ArrayList<>();

        private StmContribution(String stmId) {
            this.stmId = stmId;
        }

        private void add(AXMemoryEvent event, String effectiveMappingId, double relevance) {
            String mapping = effectiveMappingId == null || effectiveMappingId.isBlank()
                    ? "unmapped"
                    : effectiveMappingId;
            if (!effectiveMappings.add(mapping)) {
                max = Math.max(max, relevance);
                hits.add(hit(event, mapping, relevance));
                return;
            }
            max = Math.max(max, relevance);
            sum += Math.max(0.0D, relevance);
            hits.add(hit(event, mapping, relevance));
        }

        private String stmId() {
            return stmId;
        }

        private double score() {
            return max + Math.log1p(sum);
        }

        private AXMemoryRetrievalTrace toTrace() {
            return new AXMemoryRetrievalTrace(
                    stmId,
                    score(),
                    hits.stream()
                            .sorted(Comparator.comparingDouble(AXMemoryRetrievalTrace.EventHit::relevance).reversed())
                            .toList()
            );
        }

        private AXMemoryRetrievalTrace.EventHit hit(AXMemoryEvent event, String mapping, double relevance) {
            if (event == null) {
                return new AXMemoryRetrievalTrace.EventHit("", "", mapping, relevance);
            }
            return new AXMemoryRetrievalTrace.EventHit(event.id(), event.factHash(), mapping, relevance);
        }
    }
}
