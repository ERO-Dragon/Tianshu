package com.rheinmetal.tianshu.dubhe.shared.event;

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
 * cases assert the <em>shape</em> of the {@link DubheEventText} handed to the renderer (key plus arguments) and
 * leave "does that template become a good sentence" to the provider-side rendering test. The split is the
 * point of the refactor, not a gap in coverage.
 */
class DubheEventAggregatorTest {
    private static final long START = 1_000_000L;
    private static final long WINDOW = DubheEventSettings.DEFAULT_AGGREGATE_WINDOW_MILLIS;
    private static final String SOURCE = "module.probe";
    private static final String AGGREGATE_KEY = DubheEventTexts.AGGREGATE_PREFIX + "tianshu.health_change";

    /** Fold templates the aggregator asked the renderer to word, in order. */
    private final List<DubheEventText> rendered = new ArrayList<>();

    private DubheEventAggregator aggregator(String sentence) {
        return aggregator(template -> sentence);
    }

    private DubheEventAggregator aggregator(Function<DubheEventText, String> renderer) {
        return new DubheEventAggregator(DubheEventSettings.DEFAULT, template -> {
            rendered.add(template);
            return renderer.apply(template);
        });
    }

    @Test
    void repeatedOccurrencesFoldIntoALayerBuiltRangeTemplate() {
        DubheEventAggregator aggregator = aggregator("血量多次下降");

        fold(aggregator, "tianshu:health_change", "失去 5 心", 20, DubheEventFold.RANGE, SOURCE, START);
        fold(aggregator, "tianshu:health_change", "失去 4 心", 15, DubheEventFold.RANGE, SOURCE, START + 1_000L);
        fold(aggregator, "tianshu:health_change", "失去 3 心", 11, DubheEventFold.RANGE, SOURCE, START + 2_000L);

        List<DubheEventAppend> appends = aggregator.drainAll();

        assertEquals(1, appends.size());
        DubheEventAppend.Aggregated aggregated = (DubheEventAppend.Aggregated) appends.get(0);
        assertEquals("tianshu:health_change", aggregated.kind());
        assertEquals(3, aggregated.count());
        assertEquals("血量多次下降", aggregated.text());

        // The shape is the contract now: count plus the first and last magnitude, under the aggregate key.
        assertEquals(1, rendered.size());
        DubheEventText template = rendered.get(0);
        assertEquals(AGGREGATE_KEY, template.key());
        assertEquals(List.of(
                DubheEventText.Arg.value("3"),
                DubheEventText.Arg.value("20"),
                DubheEventText.Arg.value("11")
        ), template.args());
    }

    @Test
    void aCountFoldCarriesTheCountOnly() {
        DubheEventAggregator aggregator = aggregator("受伤 3 次");

        fold(aggregator, "tianshu:damage_taken", "受伤了", null, DubheEventFold.COUNT, SOURCE, START);
        fold(aggregator, "tianshu:damage_taken", "受伤了", null, DubheEventFold.COUNT, SOURCE, START + 1_000L);
        fold(aggregator, "tianshu:damage_taken", "受伤了", null, DubheEventFold.COUNT, SOURCE, START + 2_000L);

        aggregator.drainAll();

        assertEquals(1, rendered.size());
        assertEquals(DubheEventTexts.AGGREGATE_PREFIX + "tianshu.damage_taken", rendered.get(0).key());
        assertEquals(List.of(DubheEventText.Arg.value("3")), rendered.get(0).args());
    }

    @Test
    void windowsBelowTheThresholdEmitTheirOriginalOccurrencesUntouched() {
        DubheEventAggregator aggregator = aggregator("never used");
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START);
        fold(aggregator, "tianshu:mob_killed", "击杀骷髅", null, DubheEventFold.COUNT, SOURCE, START + 1_000L);

        List<DubheEventAppend> appends = aggregator.drainAll();

        assertEquals(2, appends.size());
        assertEquals(new DubheEventAppend.Single("tianshu:mob_killed", "击杀僵尸"), appends.get(0));
        assertEquals(new DubheEventAppend.Single("tianshu:mob_killed", "击杀骷髅"), appends.get(1));
        assertTrue(rendered.isEmpty(), "a window below the threshold never reaches a fold template");
    }

    @Test
    void occurrencesWithoutAFoldShapeAreNeverFoldedAndNeverGainWording() {
        DubheEventAggregator aggregator = aggregator("never used");
        DubheEventDescriptor descriptor = descriptor("mymod:ritual", null, DubheEventFold.NONE, SOURCE);

        assertFalse(aggregator.offer(descriptor, "开坛了", START));
        assertEquals(0, aggregator.openWindowCount());
        assertTrue(aggregator.drainAll().isEmpty());
    }

    @Test
    void aFoldTemplateWithoutASentenceKeepsEveryOccurrence() {
        // The renderer answering blank is the new equivalent of the old "renderer returns null": the layer
        // refuses to invent wording, so the occurrences survive as themselves.
        DubheEventAggregator aggregator = aggregator(template -> "");
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START + 1_000L);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START + 2_000L);

        List<DubheEventAppend> appends = aggregator.drainAll();

        assertEquals(3, appends.size());
        appends.forEach(append -> {
            assertTrue(append instanceof DubheEventAppend.Single);
            assertEquals("击杀僵尸", append.text());
        });
    }

    @Test
    void aRendererThatThrowsKeepsEveryOccurrence() {
        DubheEventAggregator aggregator = aggregator(template -> {
            throw new IllegalStateException("renderer bug");
        });
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START + 1_000L);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START + 2_000L);

        assertEquals(3, aggregator.drainAll().size());
    }

    /**
     * A magnitude is what a fold summarises, not what identifies the event: drops of different sizes are one window,
     * otherwise every RANGE template would be unreachable.
     */
    @Test
    void numericArgumentsDoNotSplitAWindow() {
        DubheEventAggregator aggregator = new DubheEventAggregator(threshold(2), template -> "folded");
        DubheEventDescriptor hit = DubheEventDescriptor.of("tianshu:health_change", SOURCE,
                DubheEventPayload.of(), DubheEventText.of("line.health", "5"), DubheEventFold.COUNT);

        assertTrue(aggregator.offer(hit, "失去 5 心", START));
        assertTrue(aggregator.offer(hit, "失去 4 心", START + 1_000L));

        assertEquals(1, aggregator.openWindowCount(), "the number is the fold input, not the identity");
        assertEquals(1, aggregator.drainAll().size());
    }

    @Test
    void idleWindowsAreClosedByDrainDueOnly() {
        DubheEventAggregator aggregator = aggregator("folded");
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START + 1_000L);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START + 2_000L);

        assertTrue(aggregator.drainDue(START + WINDOW - 1).isEmpty());
        List<DubheEventAppend> appends = aggregator.drainDue(START + 2_000L + WINDOW);

        assertEquals(1, appends.size());
        assertEquals(0, aggregator.openWindowCount());
    }

    @Test
    void aLateOccurrenceClosesTheIdleWindowWithoutLosingIt() {
        DubheEventAggregator aggregator = aggregator("folded");
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START + 1_000L);
        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START + 2_000L);

        fold(aggregator, "tianshu:mob_killed", "击杀僵尸", null, DubheEventFold.COUNT, SOURCE, START + WINDOW + 5_000L);

        List<DubheEventAppend> closed = aggregator.drainAll();

        assertEquals(2, closed.size());
        assertEquals(3, ((DubheEventAppend.Aggregated) closed.get(0)).count());
        assertEquals(new DubheEventAppend.Single("tianshu:mob_killed", "击杀僵尸"), closed.get(1));
    }

    @Test
    void differentSubjectsKeepTheirOwnWindowAndTheirOwnCount() {
        DubheEventAggregator perSubject = new DubheEventAggregator(threshold(2), template -> "folded");
        DubheEventDescriptor stone = DubheEventDescriptor.of("tianshu:mob_killed", SOURCE,
                DubheEventPayload.of(), DubheEventText.of("line.item", "minecraft:stone"), DubheEventFold.COUNT);
        DubheEventDescriptor dirt = DubheEventDescriptor.of("tianshu:mob_killed", SOURCE,
                DubheEventPayload.of(), DubheEventText.of("line.item", "minecraft:dirt"), DubheEventFold.COUNT);

        perSubject.offer(stone, "圆石", START);
        perSubject.offer(stone, "圆石", START + 1_000L);
        perSubject.offer(dirt, "泥土", START + 2_000L);
        perSubject.offer(dirt, "泥土", START + 3_000L);

        List<DubheEventAppend> appends = perSubject.drainAll();

        assertEquals(2, appends.size(), "two subjects, two counted lines, never one 'item x 4'");
        assertEquals(2, ((DubheEventAppend.Aggregated) appends.get(0)).count());
        assertEquals(2, ((DubheEventAppend.Aggregated) appends.get(1)).count());
    }

    private void fold(DubheEventAggregator aggregator, String kind, String sentence, Integer magnitude,
                      DubheEventFold fold, String sourceId, long nowMillis) {
        assertTrue(aggregator.offer(descriptor(kind, magnitude, fold, sourceId), sentence, nowMillis),
                "occurrence should be folded: " + kind);
    }

    private static DubheEventDescriptor descriptor(String kind, Integer magnitude, DubheEventFold fold, String sourceId) {
        return DubheEventDescriptor.of(kind, sourceId,
                magnitude == null ? DubheEventPayload.of() : DubheEventPayload.ofMagnitude(magnitude),
                DubheEventText.of(DubheEventTexts.AGGREGATE_PREFIX + "line." + kind), fold);
    }

    private static DubheEventSettings threshold(int value) {
        return new DubheEventSettings() {
            @Override
            public int aggregateThreshold() {
                return value;
            }
        };
    }
}
