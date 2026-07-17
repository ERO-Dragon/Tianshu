package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRagClient;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemoryBlockView;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManageResultPayload;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public final class AXMemoryRetriever {
    private static final float RAG_SEARCH_FLOOR = 0.01F;

    private final AXMemorySystem memorySystem;
    private final AXLlmRagClient ragClient;
    private final AXMemoryRetrievalPolicy retrievalPolicy;
    private final ConcurrentMap<String, List<CompletableFuture<LLMCacheManageResultPayload>>> pendingByRequest = new ConcurrentHashMap<>();

    public AXMemoryRetriever(AXMemorySystem memorySystem, AXLlmRagClient ragClient) {
        this(memorySystem, ragClient, AXMemoryRetrievalPolicy.DEFAULT);
    }

    public AXMemoryRetriever(AXMemorySystem memorySystem, AXLlmRagClient ragClient, AXMemoryRetrievalPolicy retrievalPolicy) {
        this.memorySystem = Objects.requireNonNull(memorySystem, "memorySystem");
        this.ragClient = Objects.requireNonNull(ragClient, "ragClient");
        this.retrievalPolicy = retrievalPolicy == null ? AXMemoryRetrievalPolicy.DEFAULT : retrievalPolicy;
    }

    public void retrieve(AXMemoryRetrievalRequest request, Completion completion) {
        Objects.requireNonNull(completion, "completion");
        Completion safeCompletion = once(completion);
        if (request == null || !request.scope().writable() || request.queryText().isBlank() || request.maxBlocks() <= 0) {
            safeCompletion.complete(AXMemoryRetrievalResult.empty());
            return;
        }
        String requestKey = request.request() == null ? "" : request.request().requestKey();
        List<CompletableFuture<LLMCacheManageResultPayload>> tracked = new CopyOnWriteArrayList<>();
        if (!requestKey.isBlank()) {
            pendingByRequest.put(requestKey, tracked);
        }
        try {
            searchAsync(request, tracked).whenComplete((result, error) -> {
                if (!requestKey.isBlank()) {
                    pendingByRequest.remove(requestKey, tracked);
                }
                safeCompletion.complete(error == null ? result : AXMemoryRetrievalResult.empty());
            });
        } catch (RuntimeException exception) {
            if (!requestKey.isBlank()) {
                pendingByRequest.remove(requestKey, tracked);
            }
            safeCompletion.complete(AXMemoryRetrievalResult.empty());
        }
    }

    public CompletableFuture<AXMemoryRetrievalResult> retrieveAsync(AXMemoryRetrievalRequest request) {
        CompletableFuture<AXMemoryRetrievalResult> future = new CompletableFuture<>();
        retrieve(request, future::complete);
        return future;
    }

    public void cancel(String requestKey, String reason) {
        if (requestKey == null || requestKey.isBlank()) {
            return;
        }
        List<CompletableFuture<LLMCacheManageResultPayload>> tracked = pendingByRequest.remove(requestKey);
        if (tracked == null) {
            return;
        }
        for (CompletableFuture<LLMCacheManageResultPayload> future : tracked) {
            ragClient.cancelFuture(future, reason);
        }
    }

    private CompletableFuture<AXMemoryRetrievalResult> searchAsync(
            AXMemoryRetrievalRequest request,
            List<CompletableFuture<LLMCacheManageResultPayload>> tracked
    ) {
        String l1Uid = AXMemoryRagUids.l1(request.scope());
        int l1TopK = Math.max(retrievalPolicy.minRoutedL1Clusters(), request.maxBlocks());
        CompletableFuture<LLMCacheManageResultPayload> l1Future = ragClient.searchUid(l1Uid, request.queryText(), l1TopK, RAG_SEARCH_FLOOR);
        tracked.add(l1Future);
        return l1Future
                .thenCompose(l1Result -> {
                    List<String> l2ClusterIds = hitEntryIds(l1Result);
                    if (l2ClusterIds.isEmpty()) {
                        return CompletableFuture.completedFuture(AXMemoryRetrievalResult.empty());
                    }
                    List<CompletableFuture<LLMCacheManageResultPayload>> futures = l2ClusterIds.stream()
                            .map(clusterId -> ragClient.searchUid(
                                    AXMemoryRagUids.l2(request.scope(), clusterId),
                                    request.queryText(),
                                    Math.max(request.maxBlocks(), retrievalPolicy.l2EffectiveMappingMaxSize()),
                                    RAG_SEARCH_FLOOR
                            ))
                            .toList();
                    tracked.addAll(futures);
                    return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> {
                                List<LLMCacheManageResultPayload> l2Results = futures.stream()
                                        .map(CompletableFuture::join)
                                        .toList();
                                return search(request, l2Results);
                            });
                });
    }

    private AXMemoryRetrievalResult search(AXMemoryRetrievalRequest request, List<LLMCacheManageResultPayload> l2Results) {
        if (l2Results == null || l2Results.isEmpty()) {
            return AXMemoryRetrievalResult.empty();
        }
        Map<String, AXMemoryEvent> eventsById = memorySystem.events().loadAll(request.scope()).stream()
                .collect(Collectors.toMap(AXMemoryEvent::id, event -> event, (first, second) -> first, LinkedHashMap::new));
        long nowMillis = System.currentTimeMillis();
        Map<String, StmContribution> contributions = new HashMap<>();
        Set<String> subColdStmIds = new HashSet<>();
        for (LLMCacheManageResultPayload result : l2Results) {
            if (result == null || !result.success() || result.hits().isEmpty()) {
                continue;
            }
            for (LLMCacheManageResultPayload.HitGroupPayload group : result.hits()) {
                String effectiveMappingId = AXMemoryRagUids.l2ClusterId(group.uid());
                for (LLMCacheManageResultPayload.HitEntryPayload hit : group.entries()) {
                    AXMemoryEvent event = eventsById.get(hit.entryId());
                    if (event == null || event.stmId().isBlank()) {
                        continue;
                    }
                    double relevance = timeWeight(event, nowMillis, hit.score());
                    if (relevance < retrievalPolicy.coldScoreThreshold()) {
                        if (relevance > 0.0D) {
                            subColdStmIds.add(event.stmId());
                        }
                        continue;
                    }
                    contributions.computeIfAbsent(event.stmId(), StmContribution::new)
                            .add(event, effectiveMappingId, relevance);
                }
            }
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
        List<SelectedBlock> selected = selectBlocks(request, contributions, subColdStmIds, blocksById, blockOrder, allBlocks);
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

    private List<String> hitEntryIds(LLMCacheManageResultPayload result) {
        if (result == null || !result.success() || result.hits().isEmpty()) {
            return List.of();
        }
        return result.hits().stream()
                .filter(Objects::nonNull)
                .flatMap(group -> group.entries().stream())
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(LLMCacheManageResultPayload.HitEntryPayload::score).reversed())
                .map(LLMCacheManageResultPayload.HitEntryPayload::entryId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private double timeWeight(AXMemoryEvent event, long nowMillis, double baseScore) {
        if (event == null || nowMillis <= 0L || event.happenedAtMillis() <= 0L) {
            return baseScore;
        }
        long ageMillis = Math.max(0L, nowMillis - event.happenedAtMillis());
        double ageDays = ageMillis / 86_400_000.0D;
        double factor = Math.max(1.0D - retrievalPolicy.maxTimeDecay(),
                1.0D - Math.min(retrievalPolicy.maxTimeDecay(), ageDays / retrievalPolicy.timeDecayDaysToMax()));
        return baseScore * factor;
    }

    private List<SelectedBlock> selectBlocks(
            AXMemoryRetrievalRequest request,
            Map<String, StmContribution> contributions,
            Set<String> subColdStmIds,
            Map<String, AXStmBlock> blocksById,
            Map<String, Integer> blockOrder,
            List<AXStmBlock> allBlocks
    ) {
        List<StmContribution> ordered = contributions.values().stream()
                .sorted(Comparator.comparingDouble(StmContribution::score).reversed())
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }
        double topScore = ordered.stream()
                .mapToDouble(StmContribution::maxRelevance)
                .max()
                .orElse(0.0D);
        List<StmContribution> hotCandidates = new ArrayList<>();
        List<StmContribution> warmCandidates = new ArrayList<>();
        List<StmContribution> coldCandidates = new ArrayList<>();
        for (StmContribution contribution : ordered) {
            double relevance = contribution.maxRelevance();
            if (relevance >= retrievalPolicy.hotScoreThreshold()) {
                hotCandidates.add(contribution);
            } else if (relevance >= retrievalPolicy.warmScoreThreshold()) {
                warmCandidates.add(contribution);
            } else if (relevance >= retrievalPolicy.coldScoreThreshold()) {
                coldCandidates.add(contribution);
            }
        }
        List<StmContribution> eligible = new ArrayList<>();
        eligible.addAll(hotCandidates);
        eligible.addAll(warmCandidates);
        eligible.addAll(coldCandidates);
        int maxBlocks = request.maxBlocks();
        int tokenBudget = request.tokenBudget();
        Set<String> seen = new HashSet<>();
        List<SelectedBlock> selected = new ArrayList<>();
        int[] tokens = {0};
        TierBudget hotTier = new TierBudget(retrievalPolicy.hotBlockBudget(maxBlocks), retrievalPolicy.hotTokenBudget(tokenBudget));
        selectFromTier(hotCandidates, hotTier, topScore, subColdStmIds, blocksById, blockOrder, allBlocks, seen, selected, tokens, maxBlocks);
        TierBudget warmTier = new TierBudget(
                Math.min(retrievalPolicy.warmBlockBudget(maxBlocks), maxBlocks - selected.size()),
                tokenBudget <= 0 ? 0 : Math.min(retrievalPolicy.warmTokenBudget(tokenBudget), Math.max(0, tokenBudget - tokens[0]))
        );
        selectFromTier(warmCandidates, warmTier, topScore, subColdStmIds, blocksById, blockOrder, allBlocks, seen, selected, tokens, maxBlocks);
        TierBudget coldTier = new TierBudget(
                Math.min(retrievalPolicy.coldBlockBudget(maxBlocks), maxBlocks - selected.size()),
                tokenBudget <= 0 ? 0 : Math.min(retrievalPolicy.coldTokenBudget(tokenBudget), Math.max(0, tokenBudget - tokens[0]))
        );
        selectFromTier(coldCandidates, coldTier, topScore, subColdStmIds, blocksById, blockOrder, allBlocks, seen, selected, tokens, maxBlocks);
        if (selected.size() < maxBlocks) {
            TierBudget remainingTier = new TierBudget(
                    maxBlocks - selected.size(),
                    tokenBudget <= 0 ? 0 : Math.max(0, tokenBudget - tokens[0])
            );
            selectFromTier(eligible, remainingTier, topScore, subColdStmIds, blocksById, blockOrder, allBlocks, seen, selected, tokens, maxBlocks);
        }
        return selected;
    }

    private void selectFromTier(
            List<StmContribution> candidates,
            TierBudget tier,
            double topScore,
            Set<String> subColdStmIds,
            Map<String, AXStmBlock> blocksById,
            Map<String, Integer> blockOrder,
            List<AXStmBlock> allBlocks,
            Set<String> seen,
            List<SelectedBlock> selected,
            int[] tokens,
            int maxBlocks
    ) {
        if (tier.blockBudget() <= 0) {
            return;
        }
        int selectedAnchors = 0;
        for (StmContribution contribution : candidates) {
            if (selectedAnchors >= tier.blockBudget()) {
                break;
            }
            if (selected.size() >= maxBlocks) {
                break;
            }
            AXStmBlock block = blocksById.get(contribution.stmId());
            if (block == null || block.isEmpty()) {
                continue;
            }
            List<AXStmBlock> chain = chainExpansionCandidates(block, contribution, topScore, subColdStmIds, blockOrder, allBlocks);
            boolean selectedAny = false;
            for (AXStmBlock candidate : chain) {
                if (selected.size() >= maxBlocks) {
                    break;
                }
                if (candidate == null || candidate.isEmpty() || !seen.add(candidate.id())) {
                    continue;
                }
                if (tier.tokenBudget() > 0 && tokens[0] + candidate.tokenCount() > tier.tokenBudget()) {
                    seen.remove(candidate.id());
                    continue;
                }
                selected.add(new SelectedBlock(candidate, blockOrder.getOrDefault(candidate.id(), Integer.MAX_VALUE)));
                tokens[0] += candidate.tokenCount();
                selectedAny = true;
            }
            if (selectedAny) {
                selectedAnchors++;
            }
        }
    }

    private record TierBudget(int blockBudget, int tokenBudget) {
    }

    private List<AXStmBlock> chainExpansionCandidates(
            AXStmBlock center,
            StmContribution contribution,
            double topScore,
            Set<String> subColdStmIds,
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
            if (!center.id().equals(candidate.id()) && subColdStmIds != null && subColdStmIds.contains(candidate.id())) {
                continue;
            }
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
        if (contribution.maxRelevance() < retrievalPolicy.chainExpansionMinScore()) {
            return false;
        }
        return contribution.maxRelevance() >= topScore * retrievalPolicy.chainExpansionScoreRatio();
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

        private double maxRelevance() {
            return max;
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
