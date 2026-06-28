package com.rheinmetal.tianshu.function.auxilium.memory;

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
    void buildsDerivedClustersWithoutRemovingAuthorityEvents() {
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

        AXMemoryRetrievalIndex.RoutedCandidates routed = index.route("diamond pickaxe", new float[]{1.0F, 0.0F});

        assertFalse(index.isEmpty());
        assertTrue(index.l1ClusterCount() >= 1);
        assertTrue(index.l2EffectiveMappingCount() >= 1);
        assertEquals(3, routed.entries().size());
        assertEquals(index.effectiveMappingId(e2.id()), index.effectiveMappingId(e3.id()));
        assertNotEquals(index.effectiveMappingId(e1.id()), index.effectiveMappingId(e2.id()));
    }

    @Test
    void entityGraphBoostsMatchedEntityEvents() {
        AXScope scope = scope();
        AXMemoryEvent tagged = event(scope, "stm_tagged", "玩家找到了村庄。", List.of("minecraft:village"));
        AXMemoryEvent plain = event(scope, "stm_plain", "玩家提到一个地点。", List.of());
        AXMemoryRetrievalIndex index = AXMemoryRetrievalIndex.build(
                List.of(tagged, plain),
                List.of(
                        vector(tagged, new float[]{0.7F, 0.3F}),
                        vector(plain, new float[]{0.7F, 0.3F})
                ),
                "test-embed:v1"
        );
        AXMemoryRetrievalIndex.RoutedCandidates routed = index.route("where is the minecraft village", new float[]{0.7F, 0.3F});
        AXMemoryRetrievalIndex.EventVectorEntry taggedEntry = routed.entries().stream()
                .filter(entry -> tagged.id().equals(entry.event().id()))
                .findFirst()
                .orElseThrow();
        AXMemoryRetrievalIndex.EventVectorEntry plainEntry = routed.entries().stream()
                .filter(entry -> plain.id().equals(entry.event().id()))
                .findFirst()
                .orElseThrow();

        double taggedScore = index.score(taggedEntry, new float[]{0.7F, 0.3F}, routed.analysis(), tagged.happenedAtMillis());
        double plainScore = index.score(plainEntry, new float[]{0.7F, 0.3F}, routed.analysis(), plain.happenedAtMillis());

        assertTrue(taggedScore > plainScore);
    }

    @Test
    void spatialDecayOnlyAffectsSpatiallyBoundEventsWhenQueryHasPosition() {
        AXScope scope = scope();
        AXMemoryEvent spatial = new AXMemoryEvent(
                "",
                "玩家在远处放置了箱子。",
                "",
                "stm_spatial",
                "stm_fact",
                scope.worldId(),
                "minecraft:overworld",
                "1000,64,1000",
                true,
                1_000L,
                1_000L,
                0,
                List.of("minecraft:chest")
        );
        AXMemoryEvent nonSpatial = new AXMemoryEvent(
                "",
                "玩家说箱子很重要。",
                "",
                "stm_non_spatial",
                "stm_fact",
                scope.worldId(),
                "minecraft:overworld",
                "1000,64,1000",
                false,
                1_000L,
                1_000L,
                0,
                List.of("minecraft:chest")
        );
        AXMemoryRetrievalIndex index = AXMemoryRetrievalIndex.build(
                List.of(spatial, nonSpatial),
                List.of(
                        vector(spatial, new float[]{1.0F, 0.0F}),
                        vector(nonSpatial, new float[]{1.0F, 0.0F})
                ),
                "test-embed:v1"
        );
        AXMemoryRetrievalIndex.RoutedCandidates routed = index.route("x=0 y=64 z=0 chest", new float[]{1.0F, 0.0F});
        AXMemoryRetrievalIndex.EventVectorEntry spatialEntry = routed.entries().stream()
                .filter(entry -> spatial.id().equals(entry.event().id()))
                .findFirst()
                .orElseThrow();
        AXMemoryRetrievalIndex.EventVectorEntry nonSpatialEntry = routed.entries().stream()
                .filter(entry -> nonSpatial.id().equals(entry.event().id()))
                .findFirst()
                .orElseThrow();

        double spatialScore = index.score(spatialEntry, new float[]{1.0F, 0.0F}, routed.analysis(), spatial.happenedAtMillis());
        double nonSpatialScore = index.score(nonSpatialEntry, new float[]{1.0F, 0.0F}, routed.analysis(), nonSpatial.happenedAtMillis());

        assertTrue(spatialScore < nonSpatialScore);
    }

    private static AXMemoryEvent event(AXScope scope, String stmId, String fact, List<String> tags) {
        return new AXMemoryEvent("", fact, "", stmId, "stm_fact", scope.worldId(), "", "", false, 1_000L, 1_000L, 0, tags);
    }

    private static AXEventVector vector(AXMemoryEvent event, float[] vector) {
        return new AXEventVector(event.id(), event.factHash(), "test-embed", "test-embed:v1", vector.length, vector, 1_000L);
    }

    private static AXScope scope() {
        return new AXScope("player", "save:Test", "Test", AXScopeKind.LOCAL_WORLD, true);
    }
}
