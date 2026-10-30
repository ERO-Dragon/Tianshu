package com.rheinmetal.tianshu.neoforge.network;

import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ordering, splitting and bounds of a tick's facts, without needing a game classpath. */
class GameFactBatchTest {

    @Test
    void factsKeepProductionOrderAndTheirOwnTimestamps() {
        GameFactBatch batch = new GameFactBatch(List.of(
                fact("first", 1_000L),
                fact("second", 1_002L),
                fact("third", 1_001L)
        ));

        List<WorldEventPayload> events = batch.toWorldEvents();

        assertEquals(List.of("first", "second", "third"),
                events.stream().map(WorldEventPayload::eventType).toList(),
                "the receiver replays the server's order, it does not re-sort");
        assertEquals(List.of(1_000L, 1_002L, 1_001L),
                events.stream().map(WorldEventPayload::occurredAtMillis).toList(),
                "each fact keeps the time it happened, not the time the batch was sent");
    }

    @Test
    void anEmptyBatchProducesNoEvents() {
        assertTrue(new GameFactBatch(List.of()).toWorldEvents().isEmpty());
    }

    @Test
    void aBatchLongerThanThePacketLimitIsSplitWithoutChangingOrder() {
        List<GameFact> facts = new ArrayList<>();
        for (int index = 0; index < GameFactBatch.MAX_FACTS + 2; index++) {
            facts.add(fact("fact-" + index, index));
        }

        List<GameFactBatch> pieces = new GameFactBatch(facts).split(GameFactBatch.MAX_FACTS);

        assertEquals(2, pieces.size());
        assertEquals(GameFactBatch.MAX_FACTS, pieces.get(0).facts().size());
        assertEquals(2, pieces.get(1).facts().size());
        assertEquals("fact-0", pieces.get(0).facts().get(0).factId());
        assertEquals("fact-" + GameFactBatch.MAX_FACTS, pieces.get(1).facts().get(0).factId());
    }

    @Test
    void factValuesAreAnImmutableSnapshot() {
        Map<String, String> source = new java.util.HashMap<>();
        source.put("k", "v");
        GameFact fact = new GameFact("id", "minecraft:overworld", 1L, source);
        source.put("k", "changed");

        assertEquals("v", fact.values().get("k"));
        assertThrows(UnsupportedOperationException.class, () -> fact.values().put("other", "x"));
    }

    @Test
    void declaredBoundsAreRejectedBeforeAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> GameFactLimits.requireFactCount(GameFactBatch.MAX_FACTS + 1));
        assertThrows(IllegalArgumentException.class,
                () -> GameFactLimits.requirePacketBytes(GameFactBatch.MAX_BYTES + 1));
    }

    private static GameFact fact(String factId, long occurredAtMillis) {
        return new GameFact(factId, "minecraft:overworld", occurredAtMillis, Map.of("k", "v"));
    }
}
