package com.rheinmetal.tianshu.function.auxilium.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Folding behaviour: what gets folded, when a window closes, and which template a fold produces.
 *
 * <p>The old cases asserted on a producer callback. The layer now builds the fold template itself, so these
 * cases assert the <em>shape</em> of the {@link AXEventText} handed to the renderer (key plus arguments) and
 * leave "does that template become a good sentence" to the provider-side rendering test. The split is the
 * point of the refactor, not a gap in coverage.
 */
class AXEventAggregatorTest {
    private static final long START = 1_000_000L;
    private static final long WINDOW = AXEventSettings.DEFAULT_AGGREGATE_WINDOW_MILLIS;
    private static final String SOURCE = "module.probe";
    private static final String AGGREGATE_KEY = AXEventTexts.AGGREGATE_PREFIX + "tianshu.health_change";

    /** Fold templates the aggregator asked the renderer to word, in order. */
    private final List<AXEventText> rendered = new ArrayList<>();

    private AXEventAggregator aggregator(String sentence) {
        return aggregator(template -> sentence);
    }

    private AXEventAggregator aggregator(Function<AXEventText, String> renderer) {
        return new AXEventAggregator(AXEventSettings.DEFAULT, template -> {
            rendered.add(template);
            return renderer.apply(template);
        });
    }

    @Test
    void repeatedOccurrencesFoldIntoALayerBuiltRangeTemplate() {
        AXEventAggregator aggregator = aggregator("血量多次下降");

        fold(aggregator, "tianshu:health_change", "失去 5 心", 20, AXEventFold.RANGE, SOURCE, START);
        fold(aggregator, "tianshu:health_change", "失去 4 心", 15, AXEventFold.RANGE, SOURCE, START + 1_000L);
        fold(aggregator, "tianshu:health_change", "失去 3 心", 11, AXEventFold.RANGE, SOURCE, START + 2_000L);

        List<AXEventAppend> appends = aggregator.drainAll();

        assertEquals(1, appends.size());
        AXEventAppend.Aggregated aggregated = (AXEventAppend.Aggregated) appends.get(0);
        assertEquals("tianshu:health_change", aggregated.kind());
        assertEquals(3, aggregated.count());
        assertEquals("血量多次下降", aggregated.text());

        // The shape is the contract now: count plus the first and last magnitude, under the aggregate key.
        assertEquals(1, rendered.size());
        AXEventText template = rendered.get(0);
        assertEquals(AGGREGATE_KEY, template.key());
        assertEquals(List.of(
                AXEventText.Arg.value("3"),
                AXEventText.Arg.value("20"),
                AXEventText.Arg.value("11")
        ), template.args());
    }

    @Test
    void aCountFoldCarriesTheCountOnly() {
        AXEventAggregator aggregator = aggregator("重生 3 次");

        fold(aggregator, "tianshu:respawn", "重生了", null, AXEventFold.COUNT, SOURCE, START);
        fold(aggregator, "tianshu:respawn", "重生了", null, AXEventFold.COUNT, SOURCE, START + 1_000L);
        fold(aggregator, "tianshu:respawn", "重生了", null, AXEventFold.COUNT, SOURCE, START + 2_000L);

        aggregator.drainAll();

        assertEquals(1, rendered.size());
        assertEquals(AXEventTexts.AGGREGATE_PREFIX + "tianshu.respawn", rendered.get(0).key());
        assertEquals(List.of(AXEventText.Arg.value("3")), rendered.get(0).args());
    }

    @Test
    void windowsBelowTheThresholdEmitTheirOriginalOccurrencesUntouched() {
        AXEventAggregator aggregator = aggregator("never used");
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START);
        fold(aggregator, "tianshu:mob_killed", "击杀骷髅", null, AXEventFold.COUNT, SOURCE, START + 1_000L);

        List<AXEventAppend> appends = aggregator.drainAll();

        assertEquals(2, appends.size());
        assertEquals(new AXEventAppend.Single("tianshu:mob_killed", "击杀僵尸"), appends.get(0));
        assertEquals(new AXEventAppend.Single("tianshu:mob_killed", "击杀骷髅"), appends.get(1));
        assertTrue(rendered.isEmpty(), "a window below the threshold never reaches a fold template");
    }

    @Test
    void occurrencesWithoutAFoldShapeAreNeverFoldedAndNeverGainWording() {
        AXEventAggregator aggregator = aggregator("never used");
        AXEventDescriptor descriptor = descriptor("mymod:ritual", null, AXEventFold.NONE, SOURCE);

        assertFalse(aggregator.offer(descriptor, AXEventTier.LOW, "开坛了", START));
        assertEquals(0, aggregator.openWindowCount());
        assertTrue(aggregator.drainAll().isEmpty());
    }

    @Test
    void aFoldTemplateWithoutASentenceKeepsEveryOccurrence() {
        // The renderer answering blank is the new equivalent of the old "renderer returns null": the layer
        // refuses to invent wording, so the occurrences survive as themselves.
        AXEventAggregator aggregator = aggregator(template -> "");
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START + 1_000L);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START + 2_000L);

        List<AXEventAppend> appends = aggregator.drainAll();

        assertEquals(3, appends.size());
        appends.forEach(append -> {
            assertTrue(append instanceof AXEventAppend.Single);
            assertEquals("击杀僵尸", append.text());
        });
    }

    @Test
    void aRendererThatThrowsKeepsEveryOccurrence() {
        AXEventAggregator aggregator = aggregator(template -> {
            throw new IllegalStateException("renderer bug");
        });
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START + 1_000L);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START + 2_000L);

        assertEquals(3, aggregator.drainAll().size());
    }

    @Test
    void highTierIsNeverFolded() {
        AXEventAggregator aggregator = aggregator("folded");

        assertFalse(aggregator.offer(descriptor("tianshu:death", null, AXEventFold.COUNT, SOURCE),
                AXEventTier.HIGH, "死了", START));
        assertFalse(aggregator.offer(descriptor("tianshu:death", null, AXEventFold.COUNT, SOURCE),
                AXEventTier.HIGH, "又死了", START + 1_000L));
        assertEquals(0, aggregator.openWindowCount());
        assertTrue(aggregator.drainAll().isEmpty());
    }

    @Test
    void idleWindowsAreClosedByDrainDueOnly() {
        AXEventAggregator aggregator = aggregator("folded");
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START + 1_000L);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START + 2_000L);

        assertTrue(aggregator.drainDue(START + WINDOW - 1).isEmpty());
        List<AXEventAppend> appends = aggregator.drainDue(START + 2_000L + WINDOW);

        assertEquals(1, appends.size());
        assertEquals(0, aggregator.openWindowCount());
    }

    @Test
    void aLateOccurrenceClosesTheIdleWindowWithoutLosingIt() {
        AXEventAggregator aggregator = aggregator("folded");
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START + 1_000L);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START + 2_000L);

        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, AXEventFold.COUNT, SOURCE, START + WINDOW + 5_000L);

        List<AXEventAppend> closed = aggregator.drainAll();

        assertEquals(2, closed.size());
        assertEquals(3, ((AXEventAppend.Aggregated) closed.get(0)).count());
        assertEquals(new AXEventAppend.Single("tianshu:mob_killed", "击杀僵尸"), closed.get(1));
    }

    @Test
    void twoProducersKeepTheirOwnWindowAndTheirOwnFold() {
        AXEventAggregator perSource = new AXEventAggregator(threshold(2), template -> "folded");
        AXEventDescriptor one = descriptor("tianshu:mob_killed", null, AXEventFold.COUNT, "module.one");
        AXEventDescriptor two = descriptor("tianshu:mob_killed", null, AXEventFold.COUNT, "plugin.two");

        perSource.offer(one, AXEventTier.MID, "一", START);
        perSource.offer(one, AXEventTier.MID, "一", START + 1_000L);
        perSource.offer(two, AXEventTier.MID, "二", START + 2_000L);
        perSource.offer(two, AXEventTier.MID, "二", START + 3_000L);

        List<AXEventAppend> appends = perSource.drainAll();

        assertEquals(2, appends.size(), "each source folds only its own occurrences");
        assertEquals(2, ((AXEventAppend.Aggregated) appends.get(0)).count());
        assertEquals(2, ((AXEventAppend.Aggregated) appends.get(1)).count());
    }

    private void fold(AXEventAggregator aggregator, String kind, String sentence, Integer magnitude,
                      AXEventFold fold, String sourceId, long nowMillis) {
        assertTrue(aggregator.offer(descriptor(kind, magnitude, fold, sourceId), AXEventTier.LOW, sentence, nowMillis),
                "occurrence should be folded: " + kind);
    }

    private static AXEventDescriptor descriptor(String kind, Integer magnitude, AXEventFold fold, String sourceId) {
        return AXEventDescriptor.of(kind, sourceId,
                magnitude == null ? AXEventPayload.of() : AXEventPayload.ofMagnitude(magnitude),
                AXEventText.of(AXEventTexts.AGGREGATE_PREFIX + "line." + kind), fold);
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
