package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index;

import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXEventVector;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXMemoryRetrievalIndexTest {
    @Test
    void buildsProjectionClustersWithoutRemovingAuthorityEvents() {
        AXScope scope = scope();
        AXMemoryEvent e1 = event(scope, "stm_1", "玩家到达村庄。", List.of("minecraft:village"));
        AXMemoryEvent e2 = event(scope, "stm_2", "玩家把钻石镐放进末影箱。", List.of("minecraft:diamond_pickaxe"));
        AXMemoryEvent e3 = event(scope, "stm_2", "末影箱里有钻石镐。", List.of("minecraft:diamond_pickaxe"));

        AXMemoryRetrievalIndex index = AXMemoryRetrievalIndex.build(
                List.of(e1, e2, e3),
                List.of(
                        vector(e1, new float[]{0.0F, 1.0F}),
                        vector(e2, new float[]{1.0F, 0.0F}),
                        vector(e3, new float[]{0.98F, 0.02F})
                ),
                "test-embed:v1"
        );

        AXMemoryRetrievalIndex.Projection projection = index.projection();

        assertFalse(index.isEmpty());
        assertTrue(index.l1ClusterCount() >= 1);
        assertTrue(index.l2EffectiveMappingCount() >= 1);
        assertEquals(3, projection.l2Clusters().stream().mapToInt(cluster -> cluster.events().size()).sum());
        assertEquals(index.effectiveMappingId(e2.id()), index.effectiveMappingId(e3.id()));
        assertNotEquals(index.effectiveMappingId(e1.id()), index.effectiveMappingId(e2.id()));
    }

    @Test
    void projectionCarriesCentroidVectorsForLlmRagEntries() {
        AXScope scope = scope();
        AXMemoryEvent e1 = event(scope, "stm_1", "玩家把钻石镐放进末影箱。", List.of("minecraft:diamond_pickaxe"));
        AXMemoryEvent e2 = event(scope, "stm_1", "末影箱里有钻石镐。", List.of("minecraft:diamond_pickaxe"));
        AXMemoryRetrievalIndex index = AXMemoryRetrievalIndex.build(
                List.of(e1, e2),
                List.of(
                        vector(e1, new float[]{1.0F, 0.0F}),
                        vector(e2, new float[]{0.8F, 0.2F})
                ),
                "test-embed:v1"
        );

        AXMemoryRetrievalIndex.ProjectionCluster cluster = index.projection().l2Clusters().stream()
                .filter(candidate -> candidate.events().size() == 2)
                .findFirst()
                .orElseThrow();

        assertEquals(2, cluster.centroid().length);
        assertTrue(cluster.centroid()[0] > cluster.centroid()[1]);
    }

    private static AXMemoryEvent event(AXScope scope, String stmId, String fact, List<String> tags) {
        return new AXMemoryEvent("", fact, "", stmId, "stm_fact", scope.worldId(), "", "", false, 1_000L, 1_000L, tags);
    }

    private static AXEventVector vector(AXMemoryEvent event, float[] vector) {
        return new AXEventVector(event.id(), event.factHash(), "test-embed", "test-embed:v1", vector.length, vector, 1_000L);
    }

    private static AXScope scope() {
        return new AXScope("player", "save:Test", "Test", AXScopeKind.LOCAL_WORLD, true);
    }
}
