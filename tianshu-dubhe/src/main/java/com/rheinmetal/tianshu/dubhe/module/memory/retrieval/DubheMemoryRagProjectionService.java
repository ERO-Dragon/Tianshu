package com.rheinmetal.tianshu.dubhe.module.memory.retrieval;

import com.rheinmetal.tianshu.dubhe.DubheModule;
import com.rheinmetal.tianshu.dubhe.core.llm.DubheLlmRagClient;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemorySystem;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheEventVector;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheMemoryEvent;
import com.rheinmetal.tianshu.dubhe.module.memory.retrieval.index.DubheMemoryRetrievalIndex;
import com.rheinmetal.tianshu.dubhe.scope.DubheScope;
import com.rheinmetal.tianshu.weaving.contract.LLMCacheManageResultPayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class DubheMemoryRagProjectionService {
    private static final String PRIVATE_VISIBILITY = "PRIVATE";

    private final DubheMemorySystem memorySystem;
    private final DubheLlmRagClient ragClient;

    public DubheMemoryRagProjectionService(DubheMemorySystem memorySystem, DubheLlmRagClient ragClient) {
        this.memorySystem = Objects.requireNonNull(memorySystem, "memorySystem");
        this.ragClient = Objects.requireNonNull(ragClient, "ragClient");
    }

    public CompletableFuture<Integer> project(DubheScope scope, String embeddingNamespace) {
        if (scope == null || !scope.writable() || embeddingNamespace == null || embeddingNamespace.isBlank()) {
            return CompletableFuture.completedFuture(0);
        }
        DubheMemoryRetrievalIndex index = memorySystem.retrievalIndex(scope, embeddingNamespace);
        if (index.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        DubheMemoryRetrievalIndex.Projection projection = index.projection();
        if (projection.l2Clusters().isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        Map<String, DubheEventVector> vectorsByEventId = vectorsByEventId(scope, embeddingNamespace);
        return registerAndClear(DubheMemoryRagUids.l1(scope))
                .thenCompose(ignored -> upsertL1(scope, projection))
                .thenCompose(count -> upsertL2(scope, projection, vectorsByEventId)
                        .thenApply(l2Count -> count + l2Count));
    }

    private CompletableFuture<Integer> upsertL1(DubheScope scope, DubheMemoryRetrievalIndex.Projection projection) {
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        String l1Uid = DubheMemoryRagUids.l1(scope);
        for (DubheMemoryRetrievalIndex.ProjectionCluster cluster : projection.l2Clusters()) {
            if (cluster == null || cluster.id().isBlank() || cluster.events().isEmpty()) {
                continue;
            }
            chain = chain.thenCompose(count -> ragClient.upsertEntry(l1Uid, cluster.id(), "", cluster.centroid())
                    .thenApply(result -> count + successful(result)));
        }
        return chain;
    }

    private CompletableFuture<Integer> upsertL2(
            DubheScope scope,
            DubheMemoryRetrievalIndex.Projection projection,
            Map<String, DubheEventVector> vectorsByEventId
    ) {
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (DubheMemoryRetrievalIndex.ProjectionCluster cluster : projection.l2Clusters()) {
            if (cluster == null || cluster.id().isBlank() || cluster.events().isEmpty()) {
                continue;
            }
            String uid = DubheMemoryRagUids.l2(scope, cluster.id());
            chain = chain.thenCompose(count -> registerAndClear(uid)
                    .thenCompose(ignored -> upsertEvents(uid, cluster.events(), vectorsByEventId))
                    .thenApply(l2Count -> count + l2Count));
        }
        return chain;
    }

    private CompletableFuture<Integer> upsertEvents(String uid, List<DubheMemoryEvent> events, Map<String, DubheEventVector> vectorsByEventId) {
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (DubheMemoryEvent event : events) {
            if (event == null || event.isEmpty()) {
                continue;
            }
            DubheEventVector vector = vectorsByEventId.get(event.id());
            float[] vectorValue = vector == null ? new float[0] : vector.vector();
            chain = chain.thenCompose(count -> ragClient.upsertEntry(uid, event.id(), event.fact(), vectorValue)
                    .thenApply(result -> count + successful(result)));
        }
        return chain;
    }

    private CompletableFuture<LLMCacheManageResultPayload> registerAndClear(String uid) {
        return ragClient.registerLibrary(uid, DubheModule.MODULE_ID, PRIVATE_VISIBILITY, List.of())
                .thenCompose(ignored -> ragClient.clearUid(uid));
    }

    private Map<String, DubheEventVector> vectorsByEventId(DubheScope scope, String embeddingNamespace) {
        Map<String, DubheEventVector> result = new LinkedHashMap<>();
        for (DubheEventVector vector : memorySystem.vectors().load(scope, embeddingNamespace)) {
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
