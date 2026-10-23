package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventAppend;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventFold;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventPayload;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventSpeechSink;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventText;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventTexts;
import com.rheinmetal.tianshu.api.LogSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service is where an event becomes a sentence: it renders once, then stores, speaks and folds.
 *
 * <p>The sentences asserted here used to be supplied by each producer. They now come from the injected
 * language table, which is what this stub stands in for — the expected wording is unchanged, only its
 * source moved. What the provider does with nesting and missing keys is covered by
 * {@link AXEventTextRenderingTest}; this class covers the service's pipeline.
 */
class AXGameEventServiceTest {
    private static final long START = 5_000_000L;
    private static final String PRODUCER = "module.probe";
    private static final String AGGREGATE_HEALTH = AXEventTexts.AGGREGATE_PREFIX + "tianshu.health_change";

    /** Stands in for the platform language table; the wording below is the fixture, not production text. */
    private static final AXGameEventTextProvider TEXTS = new AXGameEventTextProvider() {
        private static final java.util.Set<String> KEYS = java.util.Set.of(
                "line.death", "line.harvest", "line.ritual", "line.health_a", "line.health_b",
                "line.block", "line.mob", "line.folded", AGGREGATE_HEALTH);

        @Override
        public boolean exists(String key) {
            // Membership only: an answer that renders would need the aggregate arguments, which a lookup
            // does not carry.
            return KEYS.contains(key);
        }

        @Override
        public String text(String key, Object... args) {
            return switch (key) {
                case "line.death" -> "被岩浆怪杀死";
                case "line.harvest" -> "收下三株小麦";
                case "line.ritual" -> "祭坛亮了";
                case "line.health_a" -> "失去 3 点生命";
                case "line.health_b" -> "失去 4 点生命";
                case "line.block" -> "挖掉圆石";
                case "line.mob" -> "击杀僵尸";
                case "line.folded" -> "击杀僵尸";
                case AGGREGATE_HEALTH -> String.format("血量多次下降（%s 次，%s→%s）", args[0], args[1], args[2]);
                default -> "";
            };
        }
    };

    private final List<AXEventAppend> appended = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private final AXGameEventService service = new AXGameEventService(
            appended::add,
            AXEventSpeechSink.NOOP,
            TEXTS,
            new RecordingLogSink(logs)
    );

    @Test
    void theRenderedSentenceLandsInTheHistoryVerbatim() {
        service.submit(descriptor("tianshu:death", PRODUCER, AXEventPayload.of(), "line.death"), START);

        assertEquals(1, appended.size());
        assertEquals(new AXEventAppend.Single("tianshu:death", "被岩浆怪杀死"), appended.get(0));
        assertEquals("被岩浆怪杀死", appended.get(0).text());
        assertFalse(appended.get(0).text().contains("tianshu"), "no internal kind tag in the history line");
        assertFalse(appended.get(0).text().contains(PRODUCER), "no internal source tag in the history line");
    }

    @Test
    void anyModuleReachesTheSameChainWithItsOwnWording() {
        service.submit(descriptor("plugin:harvest", "plugin.farming", AXEventPayload.of(), "line.harvest"), START);
        service.submit(descriptor("tianshu:death", PRODUCER, AXEventPayload.of(), "line.death"), START + 1_000L);
        service.submit(descriptor("mymod:ritual", AXEventDescriptor.UNKNOWN_SOURCE, AXEventPayload.of(),
                "line.ritual"), START + 2_000L);

        assertEquals(3, appended.size());
        assertEquals("收下三株小麦", appended.get(0).text());
        assertEquals("被岩浆怪杀死", appended.get(1).text());
        assertEquals("祭坛亮了", appended.get(2).text());
    }

    @Test
    void thePendingSpeechCarriesTheRenderedSentence() {
        service.submit(descriptor("tianshu:death", PRODUCER, AXEventPayload.of(), "line.death"), START);

        assertTrue(logs.stream().anyMatch(line -> line.contains("ax.event.speech_pending")
                && line.contains("APPEND_AND_SPEAK")
                && line.contains("source=" + PRODUCER)
                && line.contains("text=被岩浆怪杀死")));
    }

    @Test
    void aSuppressedEventReachesHistoryWithoutAnySpeech() {
        AXEventDescriptor suppressed = new AXEventDescriptor(
                "tianshu:death", null, true, AXEventPayload.of(), AXEventText.of("line.death"),
                AXEventFold.NONE, PRODUCER);

        service.submit(suppressed, START);

        assertEquals(1, appended.size());
        assertTrue(logs.isEmpty());
    }

    @Test
    void repeatedOccurrencesLandAsOneLineRenderedByTheLayer() {
        service.submit(descriptor("tianshu:health_change", PRODUCER,
                AXEventPayload.ofMagnitude(15), "line.health_a", AXEventFold.RANGE), START);
        service.submit(descriptor("tianshu:health_change", PRODUCER,
                AXEventPayload.ofMagnitude(11), "line.health_b", AXEventFold.RANGE), START + 1_000L);
        service.submit(descriptor("tianshu:health_change", PRODUCER,
                AXEventPayload.ofMagnitude(8), "line.health_a", AXEventFold.RANGE), START + 2_000L);
        assertTrue(appended.isEmpty());

        service.flush(START + 3_000L);

        assertEquals(1, appended.size());
        assertEquals("血量多次下降（3 次，15→8）", appended.get(0).text());
    }

    @Test
    void occurrencesWithoutAFoldShapeLandAsSeparateLines() {
        service.submit(descriptor("tianshu:health_change", PRODUCER,
                AXEventPayload.ofMagnitude(15), "line.health_a"), START);
        service.submit(descriptor("tianshu:health_change", PRODUCER,
                AXEventPayload.ofMagnitude(11), "line.health_b"), START + 1_000L);

        assertEquals(2, appended.size());
        assertEquals("失去 3 点生命", appended.get(0).text());
        assertEquals("失去 4 点生命", appended.get(1).text());
    }

    @Test
    void anEventWithoutATemplateNeverReachesTheLayer() {
        // The old case asserted "the producer must render or the event is refused". Now the refusal is at the
        // descriptor boundary: no template means no descriptor. A *blank or unresolvable* template is legal
        // and falls back to the kind code, which AXEventTextRenderingTest covers.
        assertThrows(NullPointerException.class, () -> AXEventDescriptor.of(
                "plugin:harvest", "plugin.farming", AXEventPayload.of(), null));

        assertTrue(appended.isEmpty());
    }

    @Test
    void droppedEventNeverReachesHistory() {
        service.submit(descriptor("tianshu:block_break", PRODUCER, AXEventPayload.of(), "line.block"), START);

        assertTrue(appended.isEmpty());
        assertTrue(logs.isEmpty());
        service.flush(START + 60_000L);
        assertTrue(appended.isEmpty());
    }

    @Test
    void stoppedServiceIgnoresLaterSubmissions() {
        service.stop(START);

        assertTrue(service.closed());
        service.submit(descriptor("tianshu:death", PRODUCER, AXEventPayload.of(), "line.death"), START + 1_000L);

        assertTrue(appended.isEmpty());
    }

    @Test
    void stopFlushesTheOpenWindowBeforeClosing() {
        service.submit(descriptor("tianshu:mob_killed", PRODUCER, AXEventPayload.of(), "line.folded",
                AXEventFold.COUNT), START);
        service.submit(descriptor("tianshu:mob_killed", PRODUCER, AXEventPayload.of(), "line.folded",
                AXEventFold.COUNT), START + 1_000L);

        service.stop(START + 2_000L);

        assertEquals(2, appended.size(), "below the threshold the occurrences survive as themselves");
        assertTrue(service.closed());
    }

    private static AXEventDescriptor descriptor(String kind, String sourceId, AXEventPayload payload, String key) {
        return AXEventDescriptor.of(kind, sourceId, payload, AXEventText.of(key));
    }

    private static AXEventDescriptor descriptor(String kind, String sourceId, AXEventPayload payload, String key,
                                                AXEventFold fold) {
        return AXEventDescriptor.of(kind, sourceId, payload, AXEventText.of(key), fold);
    }

    private record RecordingLogSink(List<String> lines) implements LogSink {
        @Override
        public void info(String message) {
            lines.add(message);
        }

        @Override
        public void warn(String message) {
            lines.add(message);
        }

        @Override
        public void error(String message, Throwable failure) {
            lines.add(message);
        }
    }
}
