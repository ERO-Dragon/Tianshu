package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.function.auxilium.AXModule;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRagClient;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXEventVector;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index.AXMemoryRetrievalIndex;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManageResultPayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class AXMemoryRagProjectionService {
    private static final String PRIVATE_VISIBILITY = "PRIVATE";

    private final AXMemorySystem memorySystem;
    private final AXLlmRagClient ragClient;

    public AXMemoryRagProjectionService(AXMemorySystem memorySystem, AXLlmRagClient ragClient) {
        this.memorySystem = Objects.requireNonNull(memorySystem, "memorySystem");
        this.ragClient = Objects.requireNonNull(ragClient, "ragClient");
    }

    public CompletableFuture<Integer> project(AXScope scope, String embeddingNamespace) {
        if (scope == null || !scope.writable() || embeddingNamespace == null || embeddingNamespace.isBlank()) {
            return CompletableFuture.completedFuture(0);
        }
        AXMemoryRetrievalIndex index = memorySystem.retrievalIndex(scope, embeddingNamespace);
        if (index.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        AXMemoryRetrievalIndex.Projection projection = index.projection();
        if (projection.l2Clusters().isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        Map<String, AXEventVector> vectorsByEventId = vectorsByEventId(scope, embeddingNamespace);
        return registerAndClear(AXMemoryRagUids.l1(scope))
                .thenCompose(ignored -> upsertL1(scope, projection))
                .thenCompose(count -> upsertL2(scope, projection, vectorsByEventId)
                        .thenApply(l2Count -> count + l2Count));
    }

    private CompletableFuture<Integer> upsertL1(AXScope scope, AXMemoryRetrievalIndex.Projection projection) {
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        String l1Uid = AXMemoryRagUids.l1(scope);
        for (AXMemoryRetrievalIndex.ProjectionCluster cluster : projection.l2Clusters()) {
            if (cluster == null || cluster.id().isBlank() || cluster.events().isEmpty()) {
                continue;
            }
            chain = chain.thenCompose(count -> ragClient.upsertEntry(l1Uid, cluster.id(), "", cluster.centroid())
                    .thenApply(result -> count + successful(result)));
        }
        return chain;
    }

    private CompletableFuture<Integer> upsertL2(
            AXScope scope,
            AXMemoryRetrievalIndex.Projection projection,
            Map<String, AXEventVector> vectorsByEventId
    ) {
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (AXMemoryRetrievalIndex.ProjectionCluster cluster : projection.l2Clusters()) {
            if (cluster == null || cluster.id().isBlank() || cluster.events().isEmpty()) {
                continue;
            }
            String uid = AXMemoryRagUids.l2(scope, cluster.id());
            chain = chain.thenCompose(count -> registerAndClear(uid)
                    .thenCompose(ignored -> upsertEvents(uid, cluster.events(), vectorsByEventId))
                    .thenApply(l2Count -> count + l2Count));
        }
        return chain;
    }

    private CompletableFuture<Integer> upsertEvents(String uid, List<AXMemoryEvent> events, Map<String, AXEventVector> vectorsByEventId) {
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (AXMemoryEvent event : events) {
            if (event == null || event.isEmpty()) {
                continue;
            }
            AXEventVector vector = vectorsByEventId.get(event.id());
            float[] vectorValue = vector == null ? new float[0] : vector.vector();
            chain = chain.thenCompose(count -> ragClient.upsertEntry(uid, event.id(), event.fact(), vectorValue)
                    .thenApply(result -> count + successful(result)));
        }
        return chain;
    }

    private CompletableFuture<LLMCacheManageResultPayload> registerAndClear(String uid) {
        return ragClient.registerLibrary(uid, AXModule.MODULE_ID, PRIVATE_VISIBILITY, List.of())
                .thenCompose(ignored -> ragClient.clearUid(uid));
    }

    private Map<String, AXEventVector> vectorsByEventId(AXScope scope, String embeddingNamespace) {
        Map<String, AXEventVector> result = new LinkedHashMap<>();
        for (AXEventVector vector : memorySystem.vectors().load(scope, embeddingNamespace)) {
            if (vector != null && !vector.isEmpty()) {
                result.putIfAbsent(vector.eventId(), vector);
            }
        }
        return result;
    }

    private int successful(LLMCacheManageResultPayload result) {
        return result != null && result.success() ? 1 : 0;
    }
}
