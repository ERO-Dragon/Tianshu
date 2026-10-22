package com.rheinmetal.tianshu.function.auxilium.event;

import com.rheinmetal.tianshu.api.LogSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXGameEventServiceTest {
    private static final long START = 5_000_000L;
    private static final String PRODUCER = "module.probe";

    private final List<AXEventAppend> appended = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private final AXGameEventService service = new AXGameEventService(
            new AXEventDispatcher(),
            appended::add,
            new RecordingLogSink(logs)
    );

    @Test
    void theProducerSentenceLandsInTheHistoryVerbatim() {
        service.submit(AXEventDescriptor.of("tianshu:death", PRODUCER, AXEventPayload.of(), "被岩浆怪杀死"), START);

        assertEquals(1, appended.size());
        assertEquals(new AXEventAppend.Single("tianshu:death", "被岩浆怪杀死"), appended.get(0));
        assertEquals("被岩浆怪杀死", appended.get(0).text());
        assertFalse(appended.get(0).text().contains("tianshu"), "no internal kind tag in the history line");
        assertFalse(appended.get(0).text().contains(PRODUCER), "no internal source tag in the history line");
    }

    @Test
    void anyModuleReachesTheSameChainWithItsOwnSentence() {
        service.submit(AXEventDescriptor.of("plugin:harvest", "plugin.farming", AXEventPayload.of(), "收下三株小麦"),
                START);
        service.submit(AXEventDescriptor.of("tianshu:death", PRODUCER, AXEventPayload.of(), "被岩浆怪杀死"),
                START + 1_000L);
        service.submit(AXEventDescriptor.of("mymod:ritual", AXEventDescriptor.UNKNOWN_SOURCE, AXEventPayload.of(),
                "祭坛亮了"), START + 2_000L);

        assertEquals(3, appended.size());
        assertEquals("收下三株小麦", appended.get(0).text());
        assertEquals("被岩浆怪杀死", appended.get(1).text());
        assertEquals("祭坛亮了", appended.get(2).text());
    }

    @Test
    void thePendingSpeechPassesTheProducerSentenceThrough() {
        service.submit(AXEventDescriptor.of("tianshu:death", PRODUCER, AXEventPayload.of(), "被岩浆怪杀死"), START);

        assertTrue(logs.stream().anyMatch(line -> line.contains("ax.event.speech_pending")
                && line.contains("APPEND_AND_SPEAK")
                && line.contains("source=" + PRODUCER)
                && line.contains("text=被岩浆怪杀死")));
    }

    @Test
    void aSuppressedEventReachesHistoryWithoutAnySpeech() {
        AXEventDescriptor suppressed = new AXEventDescriptor(
                "tianshu:death", null, true, AXEventPayload.of(), "被岩浆怪杀死", null, PRODUCER);

        service.submit(suppressed, START);

        assertEquals(1, appended.size());
        assertTrue(logs.isEmpty());
    }

    @Test
    void repeatedOccurrencesLandAsOneLineRenderedByTheProducer() {
        AXEventAggregateRenderer renderer = (count, first, last) -> "血量多次下降（" + count + " 次，" + first + "→" + last + "）";
        service.submit(AXEventDescriptor.of("tianshu:health_change", PRODUCER,
                AXEventPayload.ofMagnitude(15), "失去 3 点生命", renderer), START);
        service.submit(AXEventDescriptor.of("tianshu:health_change", PRODUCER,
                AXEventPayload.ofMagnitude(11), "失去 4 点生命", renderer), START + 1_000L);
        service.submit(AXEventDescriptor.of("tianshu:health_change", PRODUCER,
                AXEventPayload.ofMagnitude(8), "失去 3 点生命", renderer), START + 2_000L);
        assertTrue(appended.isEmpty());

        service.flush(START + 3_000L);

        assertEquals(1, appended.size());
        assertEquals("血量多次下降（3 次，15→8）", appended.get(0).text());
    }

    @Test
    void occurrencesWithoutARendererLandAsSeparateProducerLines() {
        service.submit(AXEventDescriptor.of("tianshu:health_change", PRODUCER,
                AXEventPayload.ofMagnitude(15), "失去 3 点生命"), START);
        service.submit(AXEventDescriptor.of("tianshu:health_change", PRODUCER,
                AXEventPayload.ofMagnitude(11), "失去 4 点生命"), START + 1_000L);

        assertEquals(2, appended.size());
        assertEquals("失去 3 点生命", appended.get(0).text());
        assertEquals("失去 4 点生命", appended.get(1).text());
    }

    @Test
    void anEventWithoutARenderedSentenceNeverReachesTheLayer() {
        assertThrows(IllegalArgumentException.class, () -> AXEventDescriptor.of(
                "plugin:harvest", "plugin.farming", AXEventPayload.of(), ""));

        assertTrue(appended.isEmpty());
    }

    @Test
    void droppedEventNeverReachesHistory() {
        service.submit(AXEventDescriptor.of("tianshu:block_break", PRODUCER, AXEventPayload.of(), "挖掉圆石"), START);

        assertTrue(appended.isEmpty());
        assertTrue(logs.isEmpty());
        service.flush(START + 60_000L);
        assertTrue(appended.isEmpty());
    }

    @Test
    void stoppedServiceIgnoresLaterSubmissions() {
        service.stop(START);

        assertTrue(service.closed());
        service.submit(AXEventDescriptor.of("tianshu:death", PRODUCER, AXEventPayload.of(), "被岩浆怪杀死"),
                START + 1_000L);

        assertTrue(appended.isEmpty());
    }

    @Test
    void stopFlushesTheOpenWindowBeforeClosing() {
        AXEventAggregateRenderer renderer = (count, first, last) -> "folded " + count;
        service.submit(AXEventDescriptor.of("tianshu:mob_killed", PRODUCER, AXEventPayload.of(), "击杀僵尸", renderer),
                START);
        service.submit(AXEventDescriptor.of("tianshu:mob_killed", PRODUCER, AXEventPayload.of(), "击杀僵尸", renderer),
                START + 1_000L);

        service.stop(START + 2_000L);

        assertEquals(2, appended.size());
        assertTrue(service.closed());
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
