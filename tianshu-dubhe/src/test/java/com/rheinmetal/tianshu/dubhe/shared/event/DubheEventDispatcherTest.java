package com.rheinmetal.tianshu.dubhe.shared.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision behaviour end to end: tier and list resolve a class, the frequency gate pulls it back, and only the silent
 * class folds.
 *
 * <p>The dispatcher no longer decides whether to speak; it decides <em>how</em> an event will be handled, and the
 * service performs that. These cases therefore assert classes and fold windows, and leave queued/deferred execution to
 * the service tests.
 */
class DubheEventDispatcherTest {
    private static final long START = 1_000_000L;
    private static final long WINDOW = DubheEventSettings.DEFAULT_AGGREGATE_WINDOW_MILLIS;
    private static final String PRODUCER = "module.probe";
    private static final DubheEventFold FOLD = DubheEventFold.COUNT;

    private final DubheEventDispatcher dispatcher = new DubheEventDispatcher(
            DubheEventTierTable.builtin(),
            DubheEventSettings.DEFAULT,
            template -> "folded");

    @Test
    void anUnknownKindFallsBackToTheMostConservativeTier() {
        DubheEventDecision decision = decide(event("mymod:ritual_complete", "line.ritual"), START);

        assertEquals(DubheEventTier.FREQUENT, decision.tier(), "an unknown kind may never interrupt");
        assertEquals(DubheEventClass.FOLD, decision.eventClass(),
                "unknown kinds land on the window-only cell, never on speech");
        assertFalse(decision.speaksImmediately());
        assertFalse(decision.folded());
    }

    @Test
    void aRareNeutralKindWaitsForALullInsteadOfInterrupting() {
        DubheEventDecision decision = decide(event("tianshu:dimension_enter", "line.death"), START);

        assertEquals(DubheEventTier.RARE, decision.tier());
        assertEquals(DubheEventClass.DEFERRED, decision.eventClass());
        assertTrue(decision.buffersSpeech());
        assertTrue(decision.appends());
        assertFalse(decision.folded(), "a spoken class is never folded");
    }

    @Test
    void anExplicitlyHighlightedRareKindMayInterrupt() {
        DubheEventDecision decision = decide(event("tianshu:dimension_enter", "line.death").withHighlight(true), START);

        assertEquals(DubheEventClass.IMMEDIATE, decision.eventClass());
        assertTrue(decision.speaksImmediately());
    }

    /**
     * The shipped whitelist is the actionable warnings: a player can still act on a drowning or a boss fight. A
     * neutral kind such as taking damage stays neutral on purpose — it is context, not a line worth interrupting for.
     */
    @Test
    void theShippedWhitelistLiftsTheActionableWarnings() {
        assertEquals(DubheEventClass.IMMEDIATE, decide(event("tianshu:death", "line.death"), START).eventClass());
        assertEquals(DubheEventClass.IMMEDIATE,
                decide(event("tianshu:health_critical", "line.death"), START).eventClass());
        assertEquals(DubheEventClass.IMMEDIATE, decide(event("tianshu:drowning", "line.death"), START).eventClass());
        assertEquals(DubheEventClass.IMMEDIATE, decide(event("tianshu:on_fire", "line.death"), START).eventClass());
        assertEquals(DubheEventClass.IMMEDIATE, decide(event("tianshu:boss_engage", "line.death"), START).eventClass());

        assertEquals(DubheEventClass.SILENT, decide(event("tianshu:damage_taken", "line.death"), START).eventClass(),
                "a neutral kind outside the whitelist is only a counted history line");
    }

    /** The whitelist is not an exemption from the frequency step: dying three times still quietens the second and third. */
    @Test
    void aWhitelistedKindIsStillPulledBackByRepetition() {
        assertEquals(DubheEventClass.IMMEDIATE, decide(event("tianshu:drowning", "line.death"), START).eventClass());
        assertEquals(DubheEventClass.IMMEDIATE,
                decide(event("tianshu:drowning", "line.death"), START + 1_000L).eventClass());
        assertEquals(DubheEventClass.DEFERRED,
                decide(event("tianshu:drowning", "line.death"), START + 2_000L).eventClass());
    }

    /** A producer's highlight is a dynamic whitelist: it outranks the static blacklist row. */
    @Test
    void aHighlightOutranksTheStaticWeakening() {
        assertEquals(DubheEventClass.DROP,
                decide(event("tianshu:block_break", "line.block"), START).eventClass(),
                "the static blacklist drops a frequent kind");

        DubheEventDecision highlighted = decide(event("tianshu:block_break", "line.block").withHighlight(true), START);

        assertEquals(DubheEventClass.SILENT, highlighted.eventClass(), "one step up from DROP is a counted history line");
        assertFalse(highlighted.speaksImmediately(), "a whitelisted frequent kind still may not speak");
    }

    @Test
    void suppressionRegistersAsAWeakening() {
        DubheEventDescriptor suppressed = new DubheEventDescriptor(
                "tianshu:death", null, true, false, DubheEventPayload.of(), DubheEventText.of("line.death"),
                DubheEventFold.NONE, PRODUCER);

        DubheEventDecision decision = decide(suppressed, START);

        assertEquals(DubheEventClass.SILENT, decision.eventClass(), "rare + blacklist is silent, not dropped");
        assertEquals(DubheEventState.WEAKENED, decision.state());
    }

    /**
     * The whole attenuation chain on the kind that matters most: a whitelisted death says it at once, is still worth
     * saying on the second death, is only worth a lull on the third (the tier has become normal by then) and has become a
     * counted history line by the sixth (the tier has become frequent).
     */
    @Test
    void aWhitelistedDeathWalksTheWholeAttenuationChain() {
        assertEquals(DubheEventClass.IMMEDIATE, decide(event("tianshu:death", "line.death"), START).eventClass());
        assertEquals(DubheEventClass.IMMEDIATE,
                decide(event("tianshu:death", "line.death"), START + 1_000L).eventClass(),
                "a second death inside the window is still rare enough to interrupt");
        assertEquals(DubheEventClass.DEFERRED,
                decide(event("tianshu:death", "line.death"), START + 2_000L).eventClass(),
                "third death: the tier is corrected to normal, so the whitelist only lifts it to deferred");
        assertEquals(DubheEventTier.NORMAL, decide(event("tianshu:death", "line.death"), START + 3_000L).tier());

        for (int occurrence = 4; occurrence < 6; occurrence++) {
            decide(event("tianshu:death", "line.death"), START + occurrence * 1_000L);
        }
        DubheEventDecision sixth = decide(event("tianshu:death", "line.death"), START + 6_000L);

        assertEquals(DubheEventTier.FREQUENT, sixth.tier(), "sixth death: the tier has walked both steps");
        assertEquals(DubheEventClass.SILENT, sixth.eventClass(), "frequent + whitelist is a counted history line");
    }

    /** Both frequency thresholds are configuration: changing them changes where the chain turns. */
    @Test
    void theFrequencyThresholdsComeFromTheSettings() {
        DubheEventDispatcher impatient = new DubheEventDispatcher(
                DubheEventTierTable.builtin(),
                new DubheEventSettings() {
                    @Override
                    public int rareToNormalThreshold() {
                        return 2;
                    }

                    @Override
                    public int normalToFrequentThreshold() {
                        return 3;
                    }
                },
                template -> "folded");

        assertEquals(DubheEventClass.IMMEDIATE,
                impatient.decide(event("tianshu:death", "line.death"), "sentence", START).eventClass());
        assertEquals(DubheEventClass.DEFERRED,
                impatient.decide(event("tianshu:death", "line.death"), "sentence", START + 100L).eventClass(),
                "the configured rare threshold is already crossed by the second death");
        assertEquals(DubheEventClass.SILENT,
                impatient.decide(event("tianshu:death", "line.death"), "sentence", START + 200L).eventClass(),
                "and the configured normal threshold by the third");
    }

    @Test
    void aFrequentKindHighlightedIntoSilenceStillFolds() {
        DubheEventDecision first = decide(
                event("tianshu:health_change", "line.health", DubheEventPayload.ofMagnitude(15), FOLD)
                        .withHighlight(true),
                START);

        assertEquals(DubheEventClass.SILENT, first.eventClass());
        assertTrue(first.folded(), "a silent kind with a fold shape is retained in a window");
    }

    @Test
    void theFoldedWindowIsAppendedOnceAfterItCloses() {
        for (int occurrence = 0; occurrence < 3; occurrence++) {
            decide(event("tianshu:damage_taken", "line.hit", DubheEventPayload.of(), FOLD), START + occurrence * 100L);
        }
        assertTrue(dispatcher.drainDueAppends(START + 200L).isEmpty(), "the window is still open");

        List<DubheEventAppend> due = dispatcher.drainDueAppends(START + 200L + WINDOW);

        assertEquals(1, due.size());
        DubheEventAppend.Aggregated aggregated = (DubheEventAppend.Aggregated) due.get(0);
        assertEquals("tianshu:damage_taken", aggregated.kind());
        assertEquals(3, aggregated.count());
        assertEquals("folded", aggregated.text());
    }

    @Test
    void differentSubjectsNeverShareOneCount() {
        // Two occurrences per subject, and a fold threshold of two, so four submissions of one kind stay below the
        // frequency step while the two subjects still have to be counted apart.
        DubheEventDispatcher perSubject = new DubheEventDispatcher(
                DubheEventTierTable.builtin(),
                new DubheEventSettings() {
                    @Override
                    public int aggregateThreshold() {
                        return 2;
                    }
                },
                template -> "folded");

        for (int occurrence = 0; occurrence < 2; occurrence++) {
            perSubject.decide(event("tianshu:damage_taken", "line.hit", DubheEventPayload.of(), FOLD),
                    "sentence", START + occurrence * 100L);
            perSubject.decide(event("tianshu:damage_taken", "line.hit_other", DubheEventPayload.of(), FOLD),
                    "sentence", START + occurrence * 100L);
        }

        List<DubheEventAppend> due = perSubject.flushAppends();

        assertEquals(2, due.size(), "two subjects, two counted lines");
        assertEquals(2, ((DubheEventAppend.Aggregated) due.get(0)).count());
        assertEquals(2, ((DubheEventAppend.Aggregated) due.get(1)).count());
    }

    @Test
    void aWindowOnlyCellFoldsBelowThresholdDropAndAboveItBecomeTransient() {
        DubheEventDispatcher folding = new DubheEventDispatcher(
                DubheEventTierTable.builtin(),
                new DubheEventSettings() {
                    @Override
                    public int aggregateThreshold() {
                        return 3;
                    }
                },
                template -> "folded");

        // health_change is frequent x normal, the FOLD cell.
        for (int occurrence = 0; occurrence < 2; occurrence++) {
            DubheEventDecision decision = folding.decide(
                    event("tianshu:health_change", "line.health", DubheEventPayload.of(), FOLD),
                    "sentence", START + occurrence * 100L);
            assertEquals(DubheEventClass.FOLD, decision.eventClass());
            assertTrue(decision.folded());
        }
        assertTrue(folding.flushAppends().isEmpty(), "below the threshold a FOLD window disappears");

        for (int occurrence = 0; occurrence < 3; occurrence++) {
            folding.decide(event("tianshu:health_change", "line.health", DubheEventPayload.of(), FOLD),
                    "sentence", START + 1_000L + occurrence * 100L);
        }
        var due = folding.flushAppends();

        assertEquals(1, due.size());
        DubheEventAppend.Transient transientEntry = (DubheEventAppend.Transient) due.get(0);
        assertTrue(transientEntry.transientEntry());
        assertEquals(3, transientEntry.count());
    }
    @Test
    void anEventWithoutAFoldShapeIsNotRetained() {
        DubheEventDecision decision = decide(
                event("tianshu:damage_taken", "line.hit", DubheEventPayload.of(), DubheEventFold.NONE)
                        .withTier(DubheEventTier.NORMAL),
                START);

        assertEquals(DubheEventClass.SILENT, decision.eventClass());
        assertFalse(decision.folded(), "nothing to fold: the service appends it as itself");
    }

    private DubheEventDecision decide(DubheEventDescriptor descriptor, long nowMillis) {
        return dispatcher.decide(descriptor, "sentence", nowMillis);
    }

    private static DubheEventDescriptor event(String kind, String key) {
        return DubheEventDescriptor.of(kind, PRODUCER, DubheEventPayload.of(), DubheEventText.of(key));
    }

    private static DubheEventDescriptor event(String kind, String key, DubheEventPayload payload, DubheEventFold fold) {
        return DubheEventDescriptor.of(kind, PRODUCER, payload, DubheEventText.of(key), fold);
    }
}
