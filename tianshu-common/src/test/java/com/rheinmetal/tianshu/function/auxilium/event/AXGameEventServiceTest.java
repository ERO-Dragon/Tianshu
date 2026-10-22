package com.rheinmetal.tianshu.function.auxilium.event;

import com.rheinmetal.tianshu.api.LogSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXGameEventServiceTest {
    private static final long START = 5_000_000L;

    private final List<AXEventAppend> appended = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private final AXGameEventService service = new AXGameEventService(
            new AXEventDispatcher(),
            appended::add,
            new RecordingLogSink(logs)
    );

    @Test
    void highTierEventReachesHistoryAndReportsPendingSpeech() {
        service.submit(AXEventDescriptor.of("tianshu:death", AXEventPayload.of("被岩浆怪杀死")), START);

        assertEquals(1, appended.size());
        assertEquals(new AXEventAppend.Single("tianshu:death", "被岩浆怪杀死"), appended.get(0));
        assertEquals("tianshu:death 被岩浆怪杀死", appended.get(0).historyText());
        assertTrue(logs.stream().anyMatch(line -> line.contains("ax.event.speech_pending")
                && line.contains("APPEND_AND_SPEAK")
                && line.contains("tianshu.ax.event.speech.tianshu.death")));
    }

    @Test
    void midTierEventIsBufferedAndLandsOnFlush() {
        service.submit(AXEventDescriptor.of("tianshu:respawn", AXEventPayload.of("主世界")), START);

        assertTrue(appended.isEmpty());
        assertTrue(logs.stream().anyMatch(line -> line.contains("APPEND_AND_BUFFER")));

        service.flush(START + 1_000L);

        assertEquals(1, appended.size());
        assertEquals(new AXEventAppend.Single("tianshu:respawn", "主世界"), appended.get(0));
    }

    @Test
    void repeatedOccurrencesLandAsOneAggregateLine() {
        service.submit(AXEventDescriptor.of("tianshu:health_change", AXEventPayload.of("失去 3 心", 1, 15)), START);
        service.submit(AXEventDescriptor.of("tianshu:health_change", AXEventPayload.of("失去 4 心", 1, 11)), START + 1_000L);
        service.submit(AXEventDescriptor.of("tianshu:health_change", AXEventPayload.of("失去 3 心", 1, 8)), START + 2_000L);
        assertTrue(appended.isEmpty());

        service.flush(START + 3_000L);

        assertEquals(1, appended.size());
        assertEquals("tianshu:health_change x3 (15->8)", appended.get(0).historyText());
    }

    @Test
    void suppressedEventReachesHistoryWithoutSpeech() {
        AXEventDescriptor suppressed = new AXEventDescriptor(
                "tianshu:death", null, true, AXEventPayload.of("被岩浆怪杀死"));

        service.submit(suppressed, START);

        assertEquals(1, appended.size());
        assertTrue(logs.isEmpty());
    }

    @Test
    void droppedEventNeverReachesHistory() {
        service.submit(AXEventDescriptor.of("tianshu:block_break", AXEventPayload.of("挖掉圆石")), START);

        assertTrue(appended.isEmpty());
        assertTrue(logs.isEmpty());
        service.flush(START + 60_000L);
        assertTrue(appended.isEmpty());
    }

    @Test
    void stoppedServiceIgnoresLaterSubmissions() {
        service.stop(START);

        assertTrue(service.closed());
        service.submit(AXEventDescriptor.of("tianshu:death", AXEventPayload.of("被岩浆怪杀死")), START + 1_000L);

        assertTrue(appended.isEmpty());
    }

    @Test
    void stopFlushesTheOpenWindowBeforeClosing() {
        service.submit(AXEventDescriptor.of("tianshu:mob_killed", AXEventPayload.of("击杀僵尸")), START);
        service.submit(AXEventDescriptor.of("tianshu:mob_killed", AXEventPayload.of("击杀僵尸")), START + 1_000L);

        service.stop(START + 2_000L);

        assertEquals(2, appended.size());
        assertTrue(service.closed());
        assertFalse(appended.isEmpty());
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
