package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventAppend;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventClass;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventClassBuffer;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventFold;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventPayload;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventSettings;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventSpeechSink;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventText;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventTexts;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventTier;
import com.rheinmetal.tianshu.api.LogSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the service does with each class: queue, defer, fold, drop, and the user turn that empties both areas.
 *
 * <p>The cooldown window is pinned through an injected buffer (jitter zero) and the busy signal through settings, so
 * "not yet, then spoken" and "queued, then history" are assertions about behaviour rather than about timing luck.
 */
class AXGameEventServiceTest {
    private static final long START = 5_000_000L;
    private static final long COOLDOWN = AXEventSettings.DEFAULT_DEFERRED_COOLDOWN_BASE_MILLIS;
    private static final String PRODUCER = "module.probe";

    /** Stands in for the platform language table; the wording below is the fixture, not production text. */
    private static final AXGameEventTextProvider TEXTS = new AXGameEventTextProvider() {
        @Override
        public boolean exists(String key) {
            return key != null && (key.startsWith("line.") || key.startsWith(AXEventTexts.AGGREGATE_PREFIX));
        }

        @Override
        public String text(String key, Object... args) {
            if (key != null && key.startsWith(AXEventTexts.AGGREGATE_PREFIX)) {
                return "折叠 " + (args == null || args.length == 0 ? "?" : args[0]) + " 次";
            }
            return switch (key == null ? "" : key) {
                case "line.death" -> "被岩浆怪杀死";
                case "line.block" -> "挖掉圆石";
                case "line.health" -> "失去 3 点生命";
                case "line.stone" -> "合成圆石";
                case "line.dirt" -> "合成泥土";
                default -> "";
            };
        }
    };

    private final List<AXEventAppend> appended = new ArrayList<>();
    private final List<String> spoken = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();

    /** Busy and non-interrupting by default: the immediate queue is the case under test. */
    private AXGameEventService service(boolean busy, boolean allowInterruption) {
        return service(() -> busy, allowInterruption);
    }

    /** Same wiring with a busy signal that can change mid-test, so "the turn ended" is expressible. */
    private AXGameEventService service(java.util.function.BooleanSupplier busy, boolean allowInterruption) {
        AXEventSettings settings = new AXEventSettings() {
            @Override
            public boolean busy() {
                return busy.getAsBoolean();
            }

            @Override
            public boolean allowInterruption() {
                return allowInterruption;
            }

            @Override
            public int aggregateThreshold() {
                return 2;
            }
        };
        AXEventClassBuffer buffer = new AXEventClassBuffer(settings, () -> 0L);
        return new AXGameEventService(appended::add, (kind, text) -> spoken.add(text), TEXTS, settings, buffer,
                new RecordingLogSink(logs));
    }

    @Test
    void aDeferredKindWaitsForItsWindowAndIsThenSpokenAsOneTurn() {
        AXGameEventService service = service(false, true);

        service.submit(event("tianshu:dimension_enter", "line.death"), START);
        service.submit(event("tianshu:dimension_enter", "line.death"), START + 1_000L);

        assertTrue(spoken.isEmpty(), "a deferred event never speaks on arrival");
        assertTrue(appended.isEmpty());
        assertEquals(2, service.bufferedDeferredCount());

        service.tick(START + COOLDOWN - 1L);
        assertTrue(spoken.isEmpty(), "one millisecond before the window closes nothing happens");

        service.tick(START + COOLDOWN);
        assertEquals(List.of("被岩浆怪杀死" + '\n' + "被岩浆怪杀死"), spoken,
                "the batch is spoken as one voluntary turn");
        assertEquals(2, appended.size(), "each occurrence is still its own history line");
        assertEquals(0, service.bufferedDeferredCount());
    }

    @Test
    void aUserTurnTurnsTheDeferredBatchIntoHistory() {
        AXGameEventService service = service(false, true);
        service.submit(event("tianshu:dimension_enter", "line.death"), START);
        service.submit(event("tianshu:dimension_enter", "line.block"), START + 1_000L);

        service.beforeUserTurn(START + 2_000L);

        assertEquals(List.of("被岩浆怪杀死", "挖掉圆石"), texts(appended), "arrival order, appended as history");
        assertEquals(0, service.bufferedDeferredCount());
        assertTrue(spoken.isEmpty(), "a user turn answers with history, not with a voluntary utterance");
    }

    @Test
    void aBusyAssistantQueuesImmediateEventsUntilTheUserSpeaks() {
        AXGameEventService service = service(true, false);
        AXEventDescriptor first = event("tianshu:death", "line.death").withHighlight(true);
        AXEventDescriptor second = event("tianshu:death", "line.block").withHighlight(true);

        service.submit(first, START);
        service.submit(second, START + 1_000L);

        assertTrue(spoken.isEmpty(), "a non-interrupting player keeps the assistant quiet");
        assertEquals(2, service.queuedImmediateCount());

        service.beforeUserTurn(START + 2_000L);

        assertEquals(List.of("被岩浆怪杀死", "挖掉圆石"), texts(appended), "queued lines become history in order");
        assertEquals(0, service.queuedImmediateCount());
        assertTrue(spoken.isEmpty(), "the user already spoke: the moment for saying them has passed");

        service.beforeUserTurn(START + 3_000L);
        assertEquals(2, appended.size(), "the queue is empty after the first turn");
    }

    /**
     * The other door out of the immediate queue: the turn that blocked it ends while the player still does not want to
     * be interrupted mid-answer. The held lines are then said in one breath, and the history keeps them apart.
     */
    @Test
    void aQueuedImmediateBatchIsSpokenAsOneUtteranceWhenTheTurnEnds() {
        java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean(true);
        AXGameEventService service = service(busy::get, false);

        service.submit(event("tianshu:death", "line.death").withHighlight(true), START);
        service.submit(event("tianshu:death", "line.block").withHighlight(true), START + 1_000L);

        assertEquals(2, service.queuedImmediateCount());
        assertTrue(spoken.isEmpty());

        busy.set(false);
        service.tick(START + 2_000L);

        assertEquals(List.of("被岩浆怪杀死" + '\n' + "挖掉圆石"), spoken,
                "the queue is said as one utterance once the turn that held it is over");
        assertEquals(List.of("被岩浆怪杀死", "挖掉圆石"), texts(appended),
                "each occurrence still keeps its own history line");
        assertEquals(0, service.queuedImmediateCount());

        service.tick(START + 3_000L);
        assertEquals(1, spoken.size(), "the queue is drained, so the next tick says nothing");
    }

    /**
     * Nobody is talking, so the immediate class says its line at once — and the same line lands in the history, because a
     * sentence AX said must be a sentence AX can remember.
     */
    @Test
    void anIdleAssistantSpeaksTheImmediateEventAndWritesItToHistory() {
        AXGameEventService service = service(false, false);

        service.submit(event("tianshu:death", "line.death"), START);

        assertEquals(List.of("被岩浆怪杀死"), spoken, "an idle assistant speaks an actionable warning at once");
        assertEquals(List.of("被岩浆怪杀死"), texts(appended), "and the spoken line is its own history line");
        assertEquals(0, service.queuedImmediateCount(), "nothing had to wait");
    }

    @Test
    void anInterruptingPlayerHearsTheImmediateEventOnArrival() {
        AXGameEventService service = service(true, true);

        service.submit(event("tianshu:death", "line.death").withHighlight(true), START);

        assertEquals(List.of("被岩浆怪杀死"), spoken);
        assertEquals(0, service.queuedImmediateCount(), "an interrupted assistant does not also queue it");
    }

    @Test
    void theSilentClassFoldsIntoOneCountedLine() {
        AXGameEventService service = service(false, true);
        for (int occurrence = 0; occurrence < 3; occurrence++) {
            service.submit(event("tianshu:respawn", "line.death", AXEventFold.COUNT)
                    .withTier(AXEventTier.NORMAL), START + occurrence * 100L);
        }

        assertTrue(appended.isEmpty(), "a silent occurrence only reaches the history through its window");

        service.flush(START + 1_000L);

        assertEquals(1, appended.size());
        AXEventAppend.Aggregated aggregated = (AXEventAppend.Aggregated) appended.get(0);
        assertEquals(3, aggregated.count());
        assertEquals("折叠 3 次", aggregated.text());
    }

    @Test
    void differentSubjectsDoNotShareOneCount() {
        AXGameEventService service = service(false, true);
        service.submit(event("tianshu:respawn", "line.stone").withTier(AXEventTier.NORMAL), START);
        service.submit(event("tianshu:respawn", "line.dirt").withTier(AXEventTier.NORMAL), START + 100L);

        service.flush(START + 1_000L);

        assertEquals(List.of("合成圆石", "合成泥土"), texts(appended),
                "below the fold threshold each subject keeps its own line");
    }

    @Test
    void aDroppedEventLeavesNoTrace() {
        AXGameEventService service = service(false, true);

        service.submit(event("tianshu:health_change", "line.health"), START);
        service.flush(START + 60_000L);

        assertTrue(appended.isEmpty(), "the dropped class does not reach the history");
        assertTrue(spoken.isEmpty(), "and it is never spoken");
        assertTrue(logs.isEmpty(), "and it is not even logged: nothing happened");
    }

    /**
     * A highlight lifts a frequent kind from DROP to SILENT, and frequency stops there: the tier is already the most
     * frequent one, so a producer that marks everything ends up with one counted history line instead of silence.
     */
    @Test
    void aHighlightLiftsAFrequentEventIntoTheHistoryAndFrequencyStopsThere() {
        AXGameEventService service = service(false, true);

        for (int occurrence = 0; occurrence < 10; occurrence++) {
            service.submit(event("tianshu:health_change", "line.health", AXEventFold.COUNT).withHighlight(true),
                    START + occurrence * 100L);
        }
        service.flush(START + 2_000L);

        assertEquals(1, appended.size(), "the lifted kind is retained as one folded line");
        assertEquals(10, ((AXEventAppend.Aggregated) appended.get(0)).count(),
                "ten occurrences, and the tier cannot be corrected past frequent");
        assertTrue(spoken.isEmpty(), "a lifted frequent kind still may not speak");
    }

    @Test
    void stoppedServiceAppendsWaitingEntriesAndIgnoresLaterSubmissions() {
        AXGameEventService service = service(false, true);
        service.submit(event("tianshu:death", "line.death"), START);

        service.stop(START + 1_000L);

        assertTrue(service.closed());
        assertEquals(List.of("被岩浆怪杀死"), texts(appended), "waiting entries are owed to the history");

        service.submit(event("tianshu:death", "line.block"), START + 2_000L);
        assertEquals(1, appended.size(), "a closed service ignores later submissions");
    }

    @Test
    void anEventWithoutATemplateIsRefusedAtTheBoundary() {
        assertThrows(NullPointerException.class, () -> AXEventDescriptor.of(
                "plugin:harvest", "plugin.farming", AXEventPayload.of(), null));
    }

    /** A sentence the producer rendered itself: same class decision, and no lookup in this module's table. */
    @Test
    void aRenderedSentenceTakesTheSameDecisionPath() {
        AXGameEventService service = service(false, true);

        service.submitRendered("plugin.farming", "plugin:harvest_home", AXEventClass.DEFERRED, "麦田熟了。", START);

        assertEquals(1, service.bufferedDeferredCount(),
                "a deferred request lands in the deferred batch, exactly like a template event");
        assertTrue(spoken.isEmpty(), "and it is never spoken on arrival");

        service.tick(START + COOLDOWN);

        assertEquals(List.of("麦田熟了。"), spoken, "the producer's own sentence is what gets said");
        assertEquals(List.of("麦田熟了。"), texts(appended), "and it is the line the history keeps");
    }

    /** An immediate request is the one behaviour a producer can ask for that speaks without waiting for a lull. */
    @Test
    void anImmediateRequestSpeaksOnArrivalEvenForAnUnknownKind() {
        AXGameEventService service = service(false, true);

        service.submitRendered("plugin.farming", "plugin:harvest_home", AXEventClass.IMMEDIATE, "麦田熟了。", START);

        assertEquals(List.of("麦田熟了。"), spoken, "the request outranks the conservative unknown-kind fallback");
        assertEquals(List.of("麦田熟了。"), texts(appended), "and the spoken line is also the history line");
    }

    @Test
    void aSilentRequestOnlyReachesTheHistory() {
        AXGameEventService service = service(false, true);

        service.submitRendered("plugin.farming", "plugin:harvest_home", AXEventClass.SILENT, "麦田熟了。", START);

        assertEquals(List.of("麦田熟了。"), texts(appended), "silent means readable to the model, not audible");
        assertTrue(spoken.isEmpty());
    }

    @Test
    void aRenderedSentenceIsSanitizedLikeATemplateResult() {
        AXGameEventService service = service(false, true);

        service.submitRendered("plugin.farming", "plugin:harvest_home", AXEventClass.DEFERRED,
                "你在<主世界>完成仪式", START);
        service.tick(START + COOLDOWN);

        assertEquals(List.of("你在主世界完成仪式"), spoken,
                "angle brackets cannot smuggle a prompt tag into the history");
    }

    @Test
    void aBlankRenderedSentenceIsIgnored() {
        AXGameEventService service = service(false, true);

        service.submitRendered("plugin.farming", "plugin:harvest_home", AXEventClass.DEFERRED, "   ", START);

        assertEquals(0, service.bufferedDeferredCount());
        assertTrue(appended.isEmpty());
        assertTrue(spoken.isEmpty());
    }

    @Test
    void aMalformedKindIsRejectedBeforeAnythingIsRetained() {
        AXGameEventService service = service(false, true);

        assertThrows(IllegalArgumentException.class, () -> service.submitRendered(
                "plugin.farming", "harvest_home", AXEventClass.DEFERRED, "麦田熟了。", START));
        assertEquals(0, service.bufferedDeferredCount());
    }

    /** "Nothing happens" is expressed by not submitting: there is no behaviour to request for it. */
    @Test
    void aProducerCannotAskForDrop() {
        AXGameEventService service = service(false, true);

        assertThrows(IllegalArgumentException.class, () -> service.submitRendered(
                "plugin.farming", "plugin:harvest_home", AXEventClass.DROP, "麦田熟了。", START));
        assertTrue(appended.isEmpty());
        assertTrue(spoken.isEmpty());
    }

    private static List<String> texts(List<AXEventAppend> appends) {
        List<String> texts = new ArrayList<>(appends.size());
        for (AXEventAppend append : appends) {
            texts.add(append.text());
        }
        return texts;
    }

    private static AXEventDescriptor event(String kind, String key) {
        return AXEventDescriptor.of(kind, PRODUCER, AXEventPayload.of(), AXEventText.of(key));
    }

    private static AXEventDescriptor event(String kind, String key, AXEventFold fold) {
        return AXEventDescriptor.of(kind, PRODUCER, AXEventPayload.of(), AXEventText.of(key), fold);
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
