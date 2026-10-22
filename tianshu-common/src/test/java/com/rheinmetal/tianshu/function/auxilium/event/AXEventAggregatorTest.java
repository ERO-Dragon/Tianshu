package com.rheinmetal.tianshu.function.auxilium.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXEventAggregatorTest {
    private static final long START = 1_000_000L;
    private static final long WINDOW = AXEventSettings.DEFAULT_AGGREGATE_WINDOW_MILLIS;

    private final AXEventTierTable table = AXEventTierTable.builtin();
    private final AXEventAggregator aggregator = new AXEventAggregator(table, AXEventSettings.DEFAULT);

    @Test
    void repeatedOccurrencesFoldIntoOneEntryWithCountAndRange() {
        fold("tianshu:health_change", "失去 5 心", 20, START);
        fold("tianshu:health_change", "失去 3 心", 15, START + 1_000L);
        fold("tianshu:health_change", "失去 4 心", 11, START + 2_000L);
        fold("tianshu:health_change", "失去 3 心", 8, START + 3_000L);

        List<AXEventAppend> appends = aggregator.drainAll();

        assertEquals(1, appends.size());
        AXEventAppend.Aggregated aggregate = (AXEventAppend.Aggregated) appends.get(0);
        assertEquals("tianshu:health_change", aggregate.kind());
        assertEquals(4, aggregate.count());
        assertEquals(20, aggregate.first());
        assertEquals(8, aggregate.last());
        assertEquals(8, aggregate.min());
        assertEquals(20, aggregate.max());
        assertEquals("tianshu.ax.event.aggregate.tianshu.health_change", aggregate.sentence().key());
        assertEquals(List.of("4", "20", "8"), aggregate.sentence().args());
    }

    @Test
    void windowsBelowTheThresholdEmitTheirOriginalOccurrences() {
        fold("tianshu:mob_killed", "击杀僵尸", null, START);
        fold("tianshu:mob_killed", "击杀骷髅", null, START + 1_000L);

        List<AXEventAppend> appends = aggregator.drainAll();

        assertEquals(2, appends.size());
        assertEquals(new AXEventAppend.Single("tianshu:mob_killed", "击杀僵尸"), appends.get(0));
        assertEquals(new AXEventAppend.Single("tianshu:mob_killed", "击杀骷髅"), appends.get(1));
    }

    @Test
    void countFormSentenceCarriesTheCountOnly() {
        fold("tianshu:mob_killed", "击杀僵尸", null, START);
        fold("tianshu:mob_killed", "击杀僵尸", null, START + 1_000L);
        fold("tianshu:mob_killed", "击杀僵尸", null, START + 2_000L);

        AXEventAppend.Aggregated aggregate = (AXEventAppend.Aggregated) aggregator.drainAll().get(0);

        assertEquals(3, aggregate.count());
        assertEquals(List.of("3"), aggregate.sentence().args());
        assertNull(aggregate.first());
        assertNull(aggregate.max());
    }

    @Test
    void highTierIsNeverFolded() {
        assertFalse(aggregator.offer(event("tianshu:death", "死了", null), AXEventTier.HIGH, START));
        assertFalse(aggregator.offer(event("tianshu:death", "又死了", null), AXEventTier.HIGH, START + 1_000L));
        assertEquals(0, aggregator.openWindowCount());
        assertTrue(aggregator.drainAll().isEmpty());
    }

    @Test
    void rangeKindWithoutAMetricIsAppendedImmediately() {
        assertFalse(aggregator.offer(event("tianshu:health_change", "失去 3 心", null), AXEventTier.LOW, START));
        assertEquals(0, aggregator.openWindowCount());
    }

    @Test
    void idleWindowsAreClosedByDrainDueOnly() {
        fold("tianshu:mob_killed", "击杀僵尸", null, START);
        fold("tianshu:mob_killed", "击杀僵尸", null, START + 1_000L);
        fold("tianshu:mob_killed", "击杀僵尸", null, START + 2_000L);

        assertTrue(aggregator.drainDue(START + WINDOW - 1).isEmpty());
        List<AXEventAppend> appends = aggregator.drainDue(START + 2_000L + WINDOW);

        assertEquals(1, appends.size());
        assertEquals(0, aggregator.openWindowCount());
    }

    @Test
    void aLateOccurrenceClosesTheIdleWindowWithoutLosingIt() {
        fold("tianshu:mob_killed", "击杀僵尸", null, START);
        fold("tianshu:mob_killed", "击杀僵尸", null, START + 1_000L);
        fold("tianshu:mob_killed", "击杀僵尸", null, START + 2_000L);

        fold("tianshu:mob_killed", "击杀僵尸", null, START + WINDOW + 5_000L);

        List<AXEventAppend> closed = aggregator.drainAll();

        assertEquals(2, closed.size());
        assertEquals(3, ((AXEventAppend.Aggregated) closed.get(0)).count());
        assertEquals(new AXEventAppend.Single("tianshu:mob_killed", "击杀僵尸"), closed.get(1));
    }

    private void fold(String kind, String text, Integer magnitude, long nowMillis) {
        AXEventTier tier = table.tierOf(kind).orElse(AXEventTier.LOW);
        assertTrue(aggregator.offer(event(kind, text, magnitude), tier, nowMillis),
                "occurrence should be folded: " + kind);
    }

    private static AXEventDescriptor event(String kind, String text, Integer magnitude) {
        return AXEventDescriptor.of(kind, AXEventPayload.of(text, 1, magnitude));
    }
}
