package com.rheinmetal.tianshu.dubhe.shared.producer;

import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventAppend;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventClass;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventClassBuffer;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventDescriptor;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventFold;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventPayload;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventSettings;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventSpeechSink;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventText;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventTexts;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventTier;
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
class DubheGameEventServiceTest {
    private static final long START = 5_000_000L;
    private static final long COOLDOWN = DubheEventSettings.DEFAULT_DEFERRED_COOLDOWN_BASE_MILLIS;
    private static final String PRODUCER = "module.probe";

    /** Stands in for the platform language table; the wording below is the fixture, not production text. */
    private static final DubheGameEventTextProvider TEXTS = new DubheGameEventTextProvider() {
        @Override
        public boolean exists(String key) {
            return key != null && (key.startsWith("line.") || key.startsWith(DubheEventTexts.AGGREGATE_PREFIX));
        }

        @Override
        public String text(String key, Object... args) {
            if (key != null && key.startsWith(DubheEventTexts.AGGREGATE_PREFIX)) {
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

    private final List<DubheEventAppend> appended = new ArrayList<>();
    private final List<String> spoken = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();

    /** Busy and non-interrupting by default: the immediate queue is the case under test. */
    private DubheGameEventService service(boolean busy, boolean allowInterruption) {
        return service(() -> busy, allowInterruption);
    }

    /** Same wiring with a busy signal that can change mid-test, so "the turn ended" is expressible. */
    private DubheGameEventService service(java.util.function.BooleanSupplier busy, boolean allowInterruption) {
        DubheEventSettings settings = new DubheEventSettings() {
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
        DubheEventClassBuffer buffer = new DubheEventClassBuffer(settings, () -> 0L);
        return new DubheGameEventService(appended::add, (kind, text) -> spoken.add(text), TEXTS, settings, buffer,
                new RecordingLogSink(logs));
    }

    @Test
    void aDeferredKindWaitsForItsWindowAndIsThenSpokenAsOneTurn() {
        DubheGameEventService service = service(false, true);

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
        DubheGameEventService service = service(false, true);
        service.submit(event("tianshu:dimension_enter", "line.death"), START);
        service.submit(event("tianshu:dimension_enter", "line.block"), START + 1_000L);

        service.beforeUserTurn(START + 2_000L);

        assertEquals(List.of("被岩浆怪杀死", "挖掉圆石"), texts(appended), "arrival order, appended as history");
        assertEquals(0, service.bufferedDeferredCount());
        assertTrue(spoken.isEmpty(), "a user turn answers with history, not with a voluntary utterance");
    }

    @Test
    void aBusyAssistantQueuesImmediateEventsUntilTheUserSpeaks() {
        DubheGameEventService service = service(true, false);
        DubheEventDescriptor first = event("tianshu:death", "line.death").withHighlight(true);
        DubheEventDescriptor second = event("tianshu:death", "line.block").withHighlight(true);

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
        DubheGameEventService service = service(busy::get, false);

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
        DubheGameEventService service = service(false, false);

        service.submit(event("tianshu:death", "line.death"), START);

        assertEquals(List.of("被岩浆怪杀死"), spoken, "an idle assistant speaks an actionable warning at once");
        assertEquals(List.of("被岩浆怪杀死"), texts(appended), "and the spoken line is its own history line");
        assertEquals(0, service.queuedImmediateCount(), "nothing had to wait");
    }

    @Test
    void anInterruptingPlayerHearsTheImmediateEventOnArrival() {
        DubheGameEventService service = service(true, true);

        service.submit(event("tianshu:death", "line.death").withHighlight(true), START);

        assertEquals(List.of("被岩浆怪杀死"), spoken);
        assertEquals(0, service.queuedImmediateCount(), "an interrupted assistant does not also queue it");
    }

    @Test
    void theSilentClassAppendsEveryOccurrence() {
        DubheGameEventService service = service(false, true);
        for (int occurrence = 0; occurrence < 3; occurrence++) {
            service.submit(event("tianshu:damage_taken", "line.death", DubheEventFold.COUNT)
                    .withTier(DubheEventTier.NORMAL), START + occurrence * 100L);
        }

        assertEquals(3, appended.size(), "SILENT is a fact: every occurrence is its own history line");
        appended.forEach(entry -> assertEquals(false, entry.transientEntry()));
        assertTrue(spoken.isEmpty());
    }

    @Test
    void differentSubjectsDoNotShareOneCount() {
        DubheGameEventService service = service(false, true);
        service.submit(event("tianshu:damage_taken", "line.stone").withTier(DubheEventTier.NORMAL), START);
        service.submit(event("tianshu:damage_taken", "line.dirt").withTier(DubheEventTier.NORMAL), START + 100L);

        service.flush(START + 1_000L);

        assertEquals(List.of("合成圆石", "合成泥土"), texts(appended),
                "below the fold threshold each subject keeps its own line");
    }

    @Test
    void aDroppedEventLeavesNoTrace() {
        DubheGameEventService service = service(false, true);

        service.submit(event("tianshu:block_break", "line.block"), START);
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
        DubheGameEventService service = service(false, true);

        for (int occurrence = 0; occurrence < 10; occurrence++) {
            service.submit(event("tianshu:health_change", "line.health", DubheEventFold.COUNT).withHighlight(true),
                    START + occurrence * 100L);
        }
        service.flush(START + 2_000L);

        assertEquals(10, appended.size(), "the lifted kind is SILENT, which keeps every occurrence");
        appended.forEach(entry -> assertEquals(false, entry.transientEntry()));
        assertTrue(spoken.isEmpty(), "a lifted frequent kind still may not speak");
    }

    @Test
    void stoppedServiceAppendsWaitingEntriesAndIgnoresLaterSubmissions() {
        DubheGameEventService service = service(false, true);
        service.submit(event("tianshu:death", "line.death"), START);

        service.stop(START + 1_000L);

        assertTrue(service.closed());
        assertEquals(List.of("被岩浆怪杀死"), texts(appended), "waiting entries are owed to the history");

        service.submit(event("tianshu:death", "line.block"), START + 2_000L);
        assertEquals(1, appended.size(), "a closed service ignores later submissions");
    }

    @Test
    void anEventWithoutATemplateIsRefusedAtTheBoundary() {
        assertThrows(NullPointerException.class, () -> DubheEventDescriptor.of(
                "plugin:harvest", "plugin.farming", DubheEventPayload.of(), null));
    }

    /** A sentence the producer rendered itself: same class decision, and no lookup in this module's table. */
    @Test
    void aRenderedSentenceTakesTheSameDecisionPath() {
        DubheGameEventService service = service(false, true);

        service.submitRendered("plugin.farming", "plugin:harvest_home", DubheEventClass.DEFERRED, "麦田熟了。", START);

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
        DubheGameEventService service = service(false, true);

        service.submitRendered("plugin.farming", "plugin:harvest_home", DubheEventClass.IMMEDIATE, "麦田熟了。", START);

        assertEquals(List.of("麦田熟了。"), spoken, "the request outranks the conservative unknown-kind fallback");
        assertEquals(List.of("麦田熟了。"), texts(appended), "and the spoken line is also the history line");
    }

    @Test
    void aSilentRequestOnlyReachesTheHistory() {
        DubheGameEventService service = service(false, true);

        service.submitRendered("plugin.farming", "plugin:harvest_home", DubheEventClass.SILENT, "麦田熟了。", START);

        assertEquals(List.of("麦田熟了。"), texts(appended), "silent means readable to the model, not audible");
        assertTrue(spoken.isEmpty());
    }

    @Test
    void aRenderedSentenceIsSanitizedLikeATemplateResult() {
        DubheGameEventService service = service(false, true);

        service.submitRendered("plugin.farming", "plugin:harvest_home", DubheEventClass.DEFERRED,
                "你在<主世界>完成仪式", START);
        service.tick(START + COOLDOWN);

        assertEquals(List.of("你在主世界完成仪式"), spoken,
                "angle brackets cannot smuggle a prompt tag into the history");
    }

    @Test
    void aBlankRenderedSentenceIsIgnored() {
        DubheGameEventService service = service(false, true);

        service.submitRendered("plugin.farming", "plugin:harvest_home", DubheEventClass.DEFERRED, "   ", START);

        assertEquals(0, service.bufferedDeferredCount());
        assertTrue(appended.isEmpty());
        assertTrue(spoken.isEmpty());
    }

    @Test
    void aMalformedKindIsRejectedBeforeAnythingIsRetained() {
        DubheGameEventService service = service(false, true);

        assertThrows(IllegalArgumentException.class, () -> service.submitRendered(
                "plugin.farming", "harvest_home", DubheEventClass.DEFERRED, "麦田熟了。", START));
        assertEquals(0, service.bufferedDeferredCount());
    }

    /** "Nothing happens" is expressed by not submitting: there is no behaviour to request for it. */
    @Test
    void aProducerCannotAskForDrop() {
        DubheGameEventService service = service(false, true);

        assertThrows(IllegalArgumentException.class, () -> service.submitRendered(
                "plugin.farming", "plugin:harvest_home", DubheEventClass.DROP, "麦田熟了。", START));
        assertTrue(appended.isEmpty());
        assertTrue(spoken.isEmpty());
    }

    private static List<String> texts(List<DubheEventAppend> appends) {
        List<String> texts = new ArrayList<>(appends.size());
        for (DubheEventAppend append : appends) {
            texts.add(append.text());
        }
        return texts;
    }

    private static DubheEventDescriptor event(String kind, String key) {
        return DubheEventDescriptor.of(kind, PRODUCER, DubheEventPayload.of(), DubheEventText.of(key));
    }

    private static DubheEventDescriptor event(String kind, String key, DubheEventFold fold) {
        return DubheEventDescriptor.of(kind, PRODUCER, DubheEventPayload.of(), DubheEventText.of(key), fold);
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
