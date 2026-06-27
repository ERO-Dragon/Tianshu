package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.AXLlmPrimitiveClient;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
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

    public AXMemoryRetriever(AXMemorySystem memorySystem, AXLlmPrimitiveClient primitiveClient) {
        this.memorySystem = Objects.requireNonNull(memorySystem, "memorySystem");
        this.primitiveClient = Objects.requireNonNull(primitiveClient, "primitiveClient");
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
        List<AXMemoryEvent> events = memorySystem.events().loadAll(request.scope());
        List<AXEventVector> vectors = memorySystem.vectors().load(request.scope(), embeddingNamespace);
        if (events.isEmpty() || vectors.isEmpty()) {
            return AXMemoryRetrievalResult.empty();
        }
        Map<String, AXMemoryEvent> eventsById = new HashMap<>();
        for (AXMemoryEvent event : events) {
            eventsById.putIfAbsent(event.id(), event);
        }
        Map<String, StmContribution> contributions = new HashMap<>();
        for (AXEventVector vector : vectors) {
            AXMemoryEvent event = eventsById.get(vector.eventId());
            if (event == null || event.stmId().isBlank() || !event.factHash().equals(vector.eventFactHash())) {
                continue;
            }
            double relevance = cosine(queryVector, vector.vector());
            if (relevance <= 0.0D) {
                continue;
            }
            contributions.computeIfAbsent(event.stmId(), StmContribution::new).add(relevance);
        }
        if (contributions.isEmpty()) {
            return AXMemoryRetrievalResult.empty();
        }
        Map<String, AXStmBlock> blocksById = new LinkedHashMap<>();
        for (AXStmBlock block : memorySystem.stmBlocks().loadAll(request.scope())) {
            blocksById.putIfAbsent(block.id(), block);
        }
        List<AXStmBlock> selected = new ArrayList<>();
        int tokens = 0;
        Set<String> seen = new HashSet<>();
        List<StmContribution> ordered = contributions.values().stream()
                .sorted(Comparator.comparingDouble(StmContribution::score).reversed())
                .toList();
        for (StmContribution contribution : ordered) {
            if (selected.size() >= request.maxBlocks()) {
                break;
            }
            AXStmBlock block = blocksById.get(contribution.stmId());
            if (block == null || block.isEmpty() || !seen.add(block.id())) {
                continue;
            }
            if (!selected.isEmpty() && request.tokenBudget() > 0 && tokens + block.estimatedTokens() > request.tokenBudget()) {
                continue;
            }
            selected.add(block);
            tokens += block.estimatedTokens();
        }
        return new AXMemoryRetrievalResult(selected);
    }

    private boolean statusCompleted(LLMPrimitiveResultPayload result) {
        return result != null && LLMPrimitiveResultPayload.STATUS_COMPLETED.equals(result.status());
    }

    private boolean embeddingUsable(LLMRuntimeSnapshotPayload snapshot) {
        return snapshot != null && snapshot.embeddingAvailable() && !snapshot.embeddingNamespace().isBlank();
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0.0D;
        }
        double dot = 0.0D;
        double leftNorm = 0.0D;
        double rightNorm = 0.0D;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm <= 0.0D || rightNorm <= 0.0D) {
            return 0.0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
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

    private static final class StmContribution {
        private final String stmId;
        private double max;
        private double sum;

        private StmContribution(String stmId) {
            this.stmId = stmId;
        }

        private void add(double relevance) {
            max = Math.max(max, relevance);
            sum += Math.max(0.0D, relevance);
        }

        private String stmId() {
            return stmId;
        }

        private double score() {
            return max + Math.log1p(sum);
        }
    }
}
