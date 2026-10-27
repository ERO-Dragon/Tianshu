package com.rheinmetal.tianshu.client.presence.capture;

import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceWorldEventAggregatorTest {
    private static final String KIND = PresenceWorldEventPayload.EVENT_BLOCK_BROKEN;
    private final AtomicLong now = new AtomicLong(1_000L);

    @Test
    void identicalOccurrencesCollapseIntoOneGroupWithACount() {
        PresenceWorldEventAggregator aggregator = new PresenceWorldEventAggregator(now::get);
        for (int index = 0; index < 30; index++) {
            aggregator.accept(payload(KIND, Map.of(PresenceWorldEventPayload.VALUE_BLOCK_ID, "minecraft:stone")));
        }

        now.set(2_000L);
        PresenceWorldEventAggregator.Drain drain = aggregator.drain();

        assertEquals(1, drain.payloads().size());
        PresenceWorldEventPayload summary = drain.payloads().get(0);
        assertEquals(1, summary.occurrences().size());
        assertEquals(30, summary.occurrences().get(0).count());
        assertEquals(30, summary.totalCount());
        assertTrue(drain.capped().isEmpty());
    }

    @Test
    void differentValuesStaySeparateGroupsInsideOnePayload() {
        PresenceWorldEventAggregator aggregator = new PresenceWorldEventAggregator(now::get);
        Map<String, String> dirt = Map.of(PresenceWorldEventPayload.VALUE_ITEM_ID, "minecraft:dirt",
                PresenceWorldEventPayload.VALUE_ITEM_NAME, "泥土");
        Map<String, String> stone = Map.of(PresenceWorldEventPayload.VALUE_ITEM_ID, "minecraft:stone",
                PresenceWorldEventPayload.VALUE_ITEM_NAME, "石头");
        for (int index = 0; index < 20; index++) {
            aggregator.accept(payload(PresenceWorldEventPayload.EVENT_ITEM_PICKUP, dirt));
        }
        for (int index = 0; index < 60; index++) {
            aggregator.accept(payload(PresenceWorldEventPayload.EVENT_ITEM_PICKUP, stone));
        }

        now.set(2_000L);
        PresenceWorldEventAggregator.Drain drain = aggregator.drain();

        assertEquals(1, drain.payloads().size());
        PresenceWorldEventPayload summary = drain.payloads().get(0);
        assertEquals(2, summary.occurrences().size());
        assertEquals(20, summary.occurrences().get(0).count());
        assertEquals("minecraft:dirt", summary.occurrences().get(0).values()
                .get(PresenceWorldEventPayload.VALUE_ITEM_ID));
        assertEquals(60, summary.occurrences().get(1).count());
        assertEquals("minecraft:stone", summary.occurrences().get(1).values()
                .get(PresenceWorldEventPayload.VALUE_ITEM_ID));
        assertEquals(80, summary.totalCount());
        assertEquals("泥土", summary.values().get(PresenceWorldEventPayload.VALUE_ITEM_NAME),
                "values() keeps the single-fact reading: the first group");
    }

    @Test
    void aWindowIsPublishedOnlyOnceItsSecondHasElapsed() {
        PresenceWorldEventAggregator aggregator = new PresenceWorldEventAggregator(now::get);
        aggregator.accept(payload(KIND, Map.of(PresenceWorldEventPayload.VALUE_BLOCK_ID, "minecraft:stone")));

        now.set(1_999L);
        assertTrue(aggregator.drain().isEmpty(), "still inside the window");

        now.set(2_000L);
        assertEquals(1, aggregator.drain().payloads().size());
    }

    @Test
    void groupsBeyondTheCapAreCountedInsteadOfGrowingThePayload() {
        PresenceWorldEventAggregator aggregator = new PresenceWorldEventAggregator(now::get);
        for (int index = 0; index < PresenceWorldEventAggregator.MAX_GROUPS_PER_KIND + 1; index++) {
            aggregator.accept(payload(KIND, Map.of(PresenceWorldEventPayload.VALUE_BLOCK_ID, "block:" + index)));
        }

        now.set(2_000L);
        PresenceWorldEventAggregator.Drain drain = aggregator.drain();

        PresenceWorldEventPayload summary = drain.payloads().get(0);
        assertEquals(PresenceWorldEventAggregator.MAX_GROUPS_PER_KIND, summary.occurrences().size());
        assertEquals(1, drain.capped().get(KIND));
        assertEquals(PresenceWorldEventAggregator.MAX_GROUPS_PER_KIND, summary.totalCount());
    }

    @Test
    void aFactThatArrivesAfterTheWindowClosesStartsANewWindow() {
        PresenceWorldEventAggregator aggregator = new PresenceWorldEventAggregator(now::get);
        aggregator.accept(payload(KIND, Map.of(PresenceWorldEventPayload.VALUE_BLOCK_ID, "minecraft:stone")));

        now.set(3_000L);
        aggregator.accept(payload(KIND, Map.of(PresenceWorldEventPayload.VALUE_BLOCK_ID, "minecraft:dirt")));
        PresenceWorldEventAggregator.Drain first = aggregator.drain();

        assertEquals(1, first.payloads().size());
        assertEquals("minecraft:stone", first.payloads().get(0).values()
                .get(PresenceWorldEventPayload.VALUE_BLOCK_ID));

        now.set(4_000L);
        PresenceWorldEventAggregator.Drain second = aggregator.drain();
        assertEquals(1, second.payloads().size());
        assertEquals("minecraft:dirt", second.payloads().get(0).values()
                .get(PresenceWorldEventPayload.VALUE_BLOCK_ID));
    }

    @Test
    void resetDropsTheOpenWindows() {
        PresenceWorldEventAggregator aggregator = new PresenceWorldEventAggregator(now::get);
        aggregator.accept(payload(KIND, Map.of(PresenceWorldEventPayload.VALUE_BLOCK_ID, "minecraft:stone")));

        aggregator.reset();
        now.set(5_000L);

        assertTrue(aggregator.drain().isEmpty());
    }

    private static PresenceWorldEventPayload payload(String kind, Map<String, String> values) {
        return new PresenceWorldEventPayload(null, kind, "player", "minecraft:overworld", 1_000L, values);
    }
}
