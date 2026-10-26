package com.rheinmetal.tianshu.function.auxilium.event;

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
class AXEventDispatcherTest {
    private static final long START = 1_000_000L;
    private static final long WINDOW = AXEventSettings.DEFAULT_AGGREGATE_WINDOW_MILLIS;
    private static final String PRODUCER = "module.probe";
    private static final AXEventFold FOLD = AXEventFold.COUNT;

    private final AXEventDispatcher dispatcher = new AXEventDispatcher(
            AXEventTierTable.builtin(),
            AXEventSettings.DEFAULT,
            template -> "folded");

    @Test
    void anUnknownKindFallsBackToTheMostConservativeTier() {
        AXEventDecision decision = decide(event("mymod:ritual_complete", "line.ritual"), START);

        assertEquals(AXEventTier.FREQUENT, decision.tier(), "an unknown kind may never interrupt");
        assertEquals(AXEventClass.DROP, decision.eventClass());
        assertFalse(decision.speaksImmediately());
        assertFalse(decision.folded());
    }

    @Test
    void aRareNeutralKindWaitsForALullInsteadOfInterrupting() {
        AXEventDecision decision = decide(event("tianshu:dimension_enter", "line.death"), START);

        assertEquals(AXEventTier.RARE, decision.tier());
        assertEquals(AXEventClass.DEFERRED, decision.eventClass());
        assertTrue(decision.buffersSpeech());
        assertTrue(decision.appends());
        assertFalse(decision.folded(), "a spoken class is never folded");
    }

    @Test
    void anExplicitlyHighlightedRareKindMayInterrupt() {
        AXEventDecision decision = decide(event("tianshu:dimension_enter", "line.death").withHighlight(true), START);

        assertEquals(AXEventClass.IMMEDIATE, decision.eventClass());
        assertTrue(decision.speaksImmediately());
    }

    /**
     * The shipped whitelist is the actionable warnings: a player can still act on a drowning or a boss fight. A
     * respawn stays neutral on purpose — the death of that same moment was the line worth saying.
     */
    @Test
    void theShippedWhitelistLiftsTheActionableWarnings() {
        assertEquals(AXEventClass.IMMEDIATE, decide(event("tianshu:death", "line.death"), START).eventClass());
        assertEquals(AXEventClass.IMMEDIATE,
                decide(event("tianshu:health_critical", "line.death"), START).eventClass());
        assertEquals(AXEventClass.IMMEDIATE, decide(event("tianshu:drowning", "line.death"), START).eventClass());
        assertEquals(AXEventClass.IMMEDIATE, decide(event("tianshu:on_fire", "line.death"), START).eventClass());
        assertEquals(AXEventClass.IMMEDIATE, decide(event("tianshu:boss_engage", "line.death"), START).eventClass());

        assertEquals(AXEventClass.SILENT, decide(event("tianshu:respawn", "line.death"), START).eventClass(),
                "respawn follows the death that was already said, so it is only a counted history line");
    }

    /** The whitelist is not an exemption from the frequency step: dying three times still quietens the second and third. */
    @Test
    void aWhitelistedKindIsStillPulledBackByRepetition() {
        assertEquals(AXEventClass.IMMEDIATE, decide(event("tianshu:drowning", "line.death"), START).eventClass());
        assertEquals(AXEventClass.IMMEDIATE,
                decide(event("tianshu:drowning", "line.death"), START + 1_000L).eventClass());
        assertEquals(AXEventClass.DEFERRED,
                decide(event("tianshu:drowning", "line.death"), START + 2_000L).eventClass());
    }

    /** A producer's highlight is a dynamic whitelist: it outranks the static blacklist row. */
    @Test
    void aHighlightOutranksTheStaticBlacklist() {
        assertEquals(AXEventClass.DROP,
                decide(event("tianshu:block_break", "line.block"), START).eventClass(),
                "the static blacklist drops a frequent kind");

        AXEventDecision highlighted = decide(event("tianshu:block_break", "line.block").withHighlight(true), START);

        assertEquals(AXEventClass.SILENT, highlighted.eventClass(), "one step up from DROP is a counted history line");
        assertFalse(highlighted.speaksImmediately(), "a whitelisted frequent kind still may not speak");
    }

    @Test
    void suppressionRegistersAsABlacklistStep() {
        AXEventDescriptor suppressed = new AXEventDescriptor(
                "tianshu:death", null, true, false, AXEventPayload.of(), AXEventText.of("line.death"),
                AXEventFold.NONE, PRODUCER);

        AXEventDecision decision = decide(suppressed, START);

        assertEquals(AXEventClass.SILENT, decision.eventClass(), "rare + blacklist is silent, not dropped");
        assertTrue(decision.blacklisted());
    }

    /**
     * The whole attenuation chain on the kind that matters most: a whitelisted death says it at once, is still worth
     * saying on the second death, is only worth a lull on the third (the tier has become normal by then) and has become a
     * counted history line by the sixth (the tier has become frequent).
     */
    @Test
    void aWhitelistedDeathWalksTheWholeAttenuationChain() {
        assertEquals(AXEventClass.IMMEDIATE, decide(event("tianshu:death", "line.death"), START).eventClass());
        assertEquals(AXEventClass.IMMEDIATE,
                decide(event("tianshu:death", "line.death"), START + 1_000L).eventClass(),
                "a second death inside the window is still rare enough to interrupt");
        assertEquals(AXEventClass.DEFERRED,
                decide(event("tianshu:death", "line.death"), START + 2_000L).eventClass(),
                "third death: the tier is corrected to normal, so the whitelist only lifts it to deferred");
        assertEquals(AXEventTier.NORMAL, decide(event("tianshu:death", "line.death"), START + 3_000L).tier());

        for (int occurrence = 4; occurrence < 6; occurrence++) {
            decide(event("tianshu:death", "line.death"), START + occurrence * 1_000L);
        }
        AXEventDecision sixth = decide(event("tianshu:death", "line.death"), START + 6_000L);

        assertEquals(AXEventTier.FREQUENT, sixth.tier(), "sixth death: the tier has walked both steps");
        assertEquals(AXEventClass.SILENT, sixth.eventClass(), "frequent + whitelist is a counted history line");
    }

    /** Both frequency thresholds are configuration: changing them changes where the chain turns. */
    @Test
    void theFrequencyThresholdsComeFromTheSettings() {
        AXEventDispatcher impatient = new AXEventDispatcher(
                AXEventTierTable.builtin(),
                new AXEventSettings() {
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

        assertEquals(AXEventClass.IMMEDIATE,
                impatient.decide(event("tianshu:death", "line.death"), "sentence", START).eventClass());
        assertEquals(AXEventClass.DEFERRED,
                impatient.decide(event("tianshu:death", "line.death"), "sentence", START + 100L).eventClass(),
                "the configured rare threshold is already crossed by the second death");
        assertEquals(AXEventClass.SILENT,
                impatient.decide(event("tianshu:death", "line.death"), "sentence", START + 200L).eventClass(),
                "and the configured normal threshold by the third");
    }

    @Test
    void aFrequentKindHighlightedIntoSilenceStillFolds() {
        AXEventDecision first = decide(
                event("tianshu:health_change", "line.health", AXEventPayload.ofMagnitude(15), FOLD)
                        .withHighlight(true),
                START);

        assertEquals(AXEventClass.SILENT, first.eventClass());
        assertTrue(first.folded(), "a silent kind with a fold shape is retained in a window");
    }

    @Test
    void theFoldedWindowIsAppendedOnceAfterItCloses() {
        for (int occurrence = 0; occurrence < 3; occurrence++) {
            decide(event("tianshu:damage_taken", "line.hit", AXEventPayload.of(), FOLD), START + occurrence * 100L);
        }
        assertTrue(dispatcher.drainDueAppends(START + 200L).isEmpty(), "the window is still open");

        List<AXEventAppend> due = dispatcher.drainDueAppends(START + 200L + WINDOW);

        assertEquals(1, due.size());
        AXEventAppend.Aggregated aggregated = (AXEventAppend.Aggregated) due.get(0);
        assertEquals("tianshu:damage_taken", aggregated.kind());
        assertEquals(3, aggregated.count());
        assertEquals("folded", aggregated.text());
    }

    @Test
    void differentSubjectsNeverShareOneCount() {
        // Two occurrences per subject, and a fold threshold of two, so four submissions of one kind stay below the
        // frequency step while the two subjects still have to be counted apart.
        AXEventDispatcher perSubject = new AXEventDispatcher(
                AXEventTierTable.builtin(),
                new AXEventSettings() {
                    @Override
                    public int aggregateThreshold() {
                        return 2;
                    }
                },
                template -> "folded");

        for (int occurrence = 0; occurrence < 2; occurrence++) {
            perSubject.decide(event("tianshu:damage_taken", "line.hit", AXEventPayload.of(), FOLD),
                    "sentence", START + occurrence * 100L);
            perSubject.decide(event("tianshu:damage_taken", "line.hit_other", AXEventPayload.of(), FOLD),
                    "sentence", START + occurrence * 100L);
        }

        List<AXEventAppend> due = perSubject.flushAppends();

        assertEquals(2, due.size(), "two subjects, two counted lines");
        assertEquals(2, ((AXEventAppend.Aggregated) due.get(0)).count());
        assertEquals(2, ((AXEventAppend.Aggregated) due.get(1)).count());
    }

    @Test
    void anEventWithoutAFoldShapeIsNotRetained() {
        AXEventDecision decision = decide(
                event("tianshu:damage_taken", "line.hit", AXEventPayload.of(), AXEventFold.NONE)
                        .withTier(AXEventTier.NORMAL),
                START);

        assertEquals(AXEventClass.SILENT, decision.eventClass());
        assertFalse(decision.folded(), "nothing to fold: the service appends it as itself");
    }

    private AXEventDecision decide(AXEventDescriptor descriptor, long nowMillis) {
        return dispatcher.decide(descriptor, "sentence", nowMillis);
    }

    private static AXEventDescriptor event(String kind, String key) {
        return AXEventDescriptor.of(kind, PRODUCER, AXEventPayload.of(), AXEventText.of(key));
    }

    private static AXEventDescriptor event(String kind, String key, AXEventPayload payload, AXEventFold fold) {
        return AXEventDescriptor.of(kind, PRODUCER, payload, AXEventText.of(key), fold);
    }
}
