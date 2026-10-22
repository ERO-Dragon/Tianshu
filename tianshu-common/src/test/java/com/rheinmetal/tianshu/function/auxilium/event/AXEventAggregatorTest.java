package com.rheinmetal.tianshu.function.auxilium.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXEventAggregatorTest {
    private static final long START = 1_000_000L;
    private static final long WINDOW = AXEventSettings.DEFAULT_AGGREGATE_WINDOW_MILLIS;
    private static final String SOURCE = "module.probe";

    private final AXEventAggregator aggregator = new AXEventAggregator(AXEventSettings.DEFAULT);

    @Test
    void repeatedOccurrencesFoldThroughTheProducerRenderer() {
        List<String> calls = new ArrayList<>();
        AXEventAggregateRenderer renderer = (count, first, last) -> {
            calls.add(count + "|" + first + "|" + last);
            return "血量多次下降（" + count + " 次，" + first + "→" + last + "）";
        };

        fold("tianshu:health_change", "失去 5 心", 20, renderer, SOURCE, START);
        fold("tianshu:health_change", "失去 4 心", 15, renderer, SOURCE, START + 1_000L);
        fold("tianshu:health_change", "失去 3 心", 11, renderer, SOURCE, START + 2_000L);

        List<AXEventAppend> appends = aggregator.drainAll();

        assertEquals(1, appends.size());
        AXEventAppend.Aggregated aggregated = (AXEventAppend.Aggregated) appends.get(0);
        assertEquals("tianshu:health_change", aggregated.kind());
        assertEquals(3, aggregated.count());
        assertEquals("血量多次下降（3 次，20→11）", aggregated.text());
        assertEquals(List.of("3|20|11"), calls);
    }

    @Test
    void windowsBelowTheThresholdEmitTheirOriginalOccurrencesUntouched() {
        AXEventAggregateRenderer renderer = (count, first, last) -> "never used";
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START);
        fold("tianshu:mob_killed", "击杀骷髅", null, renderer, SOURCE, START + 1_000L);

        List<AXEventAppend> appends = aggregator.drainAll();

        assertEquals(2, appends.size());
        assertEquals(new AXEventAppend.Single("tianshu:mob_killed", "击杀僵尸"), appends.get(0));
        assertEquals(new AXEventAppend.Single("tianshu:mob_killed", "击杀骷髅"), appends.get(1));
    }

    @Test
    void occurrencesWithoutARendererAreNeverFoldedAndNeverGainWording() {
        AXEventDescriptor descriptor = descriptor("mymod:ritual", "开坛了", null, null, SOURCE);

        assertFalse(aggregator.offer(descriptor, AXEventTier.LOW, START));
        assertEquals(0, aggregator.openWindowCount());
        assertTrue(aggregator.drainAll().isEmpty());
    }

    @Test
    void aRendererWithoutASentenceKeepsEveryOccurrence() {
        AXEventAggregateRenderer renderer = (count, first, last) -> null;
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START);
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START + 1_000L);
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START + 2_000L);

        List<AXEventAppend> appends = aggregator.drainAll();

        assertEquals(3, appends.size());
        appends.forEach(append -> {
            assertTrue(append instanceof AXEventAppend.Single);
            assertEquals("击杀僵尸", append.text());
        });
    }

    @Test
    void aRendererThatThrowsKeepsEveryOccurrence() {
        AXEventAggregateRenderer renderer = (count, first, last) -> {
            throw new IllegalStateException("producer bug");
        };
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START);
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START + 1_000L);
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START + 2_000L);

        assertEquals(3, aggregator.drainAll().size());
    }

    @Test
    void highTierIsNeverFolded() {
        AXEventAggregateRenderer renderer = (count, first, last) -> "folded";

        assertFalse(aggregator.offer(descriptor("tianshu:death", "死了", null, renderer, SOURCE),
                AXEventTier.HIGH, START));
        assertFalse(aggregator.offer(descriptor("tianshu:death", "又死了", null, renderer, SOURCE),
                AXEventTier.HIGH, START + 1_000L));
        assertEquals(0, aggregator.openWindowCount());
        assertTrue(aggregator.drainAll().isEmpty());
    }

    @Test
    void idleWindowsAreClosedByDrainDueOnly() {
        AXEventAggregateRenderer renderer = (count, first, last) -> "folded " + count;
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START);
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START + 1_000L);
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START + 2_000L);

        assertTrue(aggregator.drainDue(START + WINDOW - 1).isEmpty());
        List<AXEventAppend> appends = aggregator.drainDue(START + 2_000L + WINDOW);

        assertEquals(1, appends.size());
        assertEquals(0, aggregator.openWindowCount());
    }

    @Test
    void aLateOccurrenceClosesTheIdleWindowWithoutLosingIt() {
        AXEventAggregateRenderer renderer = (count, first, last) -> "folded " + count;
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START);
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START + 1_000L);
        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START + 2_000L);

        fold("tianshu:mob_killed", "击杀僵尸", null, renderer, SOURCE, START + WINDOW + 5_000L);

        List<AXEventAppend> closed = aggregator.drainAll();

        assertEquals(2, closed.size());
        assertEquals(3, ((AXEventAppend.Aggregated) closed.get(0)).count());
        assertEquals(new AXEventAppend.Single("tianshu:mob_killed", "击杀僵尸"), closed.get(1));
    }

    @Test
    void twoProducersKeepTheirOwnWindowSoEachRendererOnlyFoldsItsOwnOccurrences() {
        AXEventAggregator perSource = new AXEventAggregator(threshold(2));
        AXEventAggregateRenderer first = (count, start, end) -> "first " + count;
        AXEventAggregateRenderer second = (count, start, end) -> "second " + count;

        perSource.offer(descriptor("tianshu:mob_killed", "击杀僵尸", null, first, "module.one"), AXEventTier.MID, START);
        perSource.offer(descriptor("tianshu:mob_killed", "击杀僵尸", null, first, "module.one"),
                AXEventTier.MID, START + 1_000L);
        perSource.offer(descriptor("tianshu:mob_killed", "击杀僵尸", null, second, "plugin.two"), AXEventTier.MID,
                START + 2_000L);
        perSource.offer(descriptor("tianshu:mob_killed", "击杀僵尸", null, second, "plugin.two"), AXEventTier.MID,
                START + 3_000L);

        List<AXEventAppend> appends = perSource.drainAll();

        assertEquals(2, appends.size());
        assertEquals("first 2", appends.get(0).text());
        assertEquals("second 2", appends.get(1).text());
    }

    private void fold(String kind, String text, Integer magnitude, AXEventAggregateRenderer renderer,
                      String sourceId, long nowMillis) {
        assertTrue(aggregator.offer(descriptor(kind, text, magnitude, renderer, sourceId), AXEventTier.LOW, nowMillis),
                "occurrence should be folded: " + kind);
    }

    private static AXEventDescriptor descriptor(String kind, String text, Integer magnitude,
                                                AXEventAggregateRenderer renderer, String sourceId) {
        return AXEventDescriptor.of(kind, sourceId,
                magnitude == null ? AXEventPayload.of() : AXEventPayload.ofMagnitude(magnitude), text, renderer);
    }

    private static AXEventSettings threshold(int value) {
        return new AXEventSettings() {
            @Override
            public int aggregateThreshold() {
                return value;
            }
        };
    }
}
