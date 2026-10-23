package com.rheinmetal.tianshu.function.auxilium.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXEventDispatcherTest {
    private static final long START = 1_000_000L;
    private static final String PRODUCER = "module.probe";
    private static final AXEventFold FOLD = AXEventFold.COUNT;

    private final AXEventDispatcher dispatcher = new AXEventDispatcher(
            AXEventTierTable.builtin(),
            AXEventSettings.DEFAULT,
            // The layer words a fold itself now; this renderer is the language table standing in for a real one.
            template -> "folded " + ((AXEventText.Arg.Value) template.args().get(0)).text());

    @Test
    void unknownKindFallsBackToLowAndOnlyAppends() {
        AXEventDecision decision = decide(
                event("mymod:ritual_complete", "仪式完成"), START);

        assertEquals(AXEventTier.LOW, decision.tier());
        assertFalse(decision.blacklisted());
        assertEquals(AXEventAction.APPEND_ONLY, decision.action());
        assertFalse(decision.action().speaks());
        assertFalse(decision.folded());
    }

    @Test
    void highTierAppendsAndSpeaksImmediately() {
        AXEventDecision decision = decide(event("tianshu:death", "被岩浆怪杀死"), START);

        assertEquals(AXEventTier.HIGH, decision.tier());
        assertEquals(AXEventAction.APPEND_AND_SPEAK, decision.action());
        assertTrue(decision.speaksImmediately());
        assertTrue(decision.appends());
        assertFalse(decision.folded());
    }

    @Test
    void highTierIsNeverFoldedEvenWithARenderer() {
        AXEventDecision decision = decide(
                event("tianshu:death", "被岩浆怪杀死", AXEventPayload.of(), FOLD), START);

        assertFalse(decision.folded());
        assertEquals(AXEventAction.APPEND_AND_SPEAK, decision.action());
        assertTrue(dispatcher.flushAppends().isEmpty());
    }

    @Test
    void highTierWithSuppressOnlyAppends() {
        AXEventDescriptor descriptor = new AXEventDescriptor(
                "tianshu:death", null, true, AXEventPayload.of(), AXEventText.of("test.line", "被岩浆怪杀死"), null, PRODUCER);

        AXEventDecision decision = decide(descriptor, START);

        assertEquals(AXEventTier.HIGH, decision.tier());
        assertTrue(decision.blacklisted());
        assertEquals(AXEventAction.APPEND_ONLY, decision.action());
        assertFalse(decision.action().speaks());
    }

    @Test
    void midTierBuffersItsSpeechAndFoldsWhenTheProducerCanRenderTheWindow() {
        AXEventDecision decision = decide(
                event("tianshu:respawn", "在主世界重生点", AXEventPayload.of(), FOLD), START);

        assertEquals(AXEventTier.MID, decision.tier());
        assertEquals(AXEventAction.APPEND_AND_BUFFER, decision.action());
        assertFalse(decision.speaksImmediately());
        assertTrue(decision.buffersSpeech());
        assertTrue(decision.folded());
    }

    @Test
    void midTierWithoutARendererAppendsImmediatelyInsteadOfInventingAFold() {
        AXEventDecision decision = decide(event("tianshu:respawn", "在主世界重生点"), START);

        assertEquals(AXEventAction.APPEND_AND_BUFFER, decision.action());
        assertFalse(decision.folded());
        assertTrue(decision.appends());
    }

    @Test
    void midTierBlacklistedOnlyAppends() {
        AXEventDescriptor descriptor = new AXEventDescriptor(
                "tianshu:respawn", null, true, AXEventPayload.of(), AXEventText.of("test.line", "在主世界重生点"), FOLD, PRODUCER);

        AXEventDecision decision = decide(descriptor, START);

        assertEquals(AXEventAction.APPEND_ONLY, decision.action());
        assertFalse(decision.action().speaks());
        assertTrue(decision.folded());
    }

    @Test
    void lowTierWithoutBlacklistOnlyAppends() {
        AXEventDecision decision = decide(
                event("tianshu:health_change", "失去 3 心", AXEventPayload.ofMagnitude(15), FOLD), START);

        assertEquals(AXEventTier.LOW, decision.tier());
        assertEquals(AXEventAction.APPEND_ONLY, decision.action());
        assertTrue(decision.folded());
        assertTrue(decision.appends());
    }

    @Test
    void lowTierWithBuiltInBlacklistIsDroppedCompletely() {
        AXEventDecision decision = decide(event("tianshu:block_break", "挖掉圆石"), START);

        assertEquals(AXEventAction.DROP, decision.action());
        assertFalse(decision.appends());
        assertFalse(decision.folded());
        assertTrue(dispatcher.flushAppends().isEmpty());
    }

    @Test
    void suppressOverridesABuiltInNonBlacklistedKind() {
        AXEventDescriptor notBlacklisted = new AXEventDescriptor(
                "tianshu:block_place", null, false, AXEventPayload.of(), AXEventText.of("test.line", "放下工作台"), null, PRODUCER);
        AXEventDescriptor suppressed = new AXEventDescriptor(
                "tianshu:health_critical", null, true, AXEventPayload.of(), AXEventText.of("test.line", "血量 2 心"), null, PRODUCER);

        assertEquals(AXEventAction.DROP, decide(notBlacklisted, START).action());
        AXEventDecision decision = decide(suppressed, START);
        assertTrue(decision.blacklisted());
        assertEquals(AXEventAction.APPEND_ONLY, decision.action());
    }

    @Test
    void declaredTierWinsOverTheBuiltInTable() {
        AXEventDescriptor promoted = event("tianshu:respawn", "在主世界重生点")
                .withTier(AXEventTier.HIGH);
        AXEventDescriptor promotedSilentKind = event("tianshu:chat_message", "小明：借我点铁")
                .withTier(AXEventTier.HIGH);

        AXEventDecision decision = decide(promoted, START);
        AXEventDecision silentKind = decide(promotedSilentKind, START + 400_000L);

        assertEquals(AXEventTier.HIGH, decision.tier());
        assertEquals(AXEventAction.APPEND_AND_SPEAK, decision.action());
        assertEquals(AXEventAction.APPEND_AND_SPEAK, silentKind.action());
    }

    @Test
    void globalGapTurnsASecondHighKindIntoABufferedSpeech() {
        assertEquals(AXEventAction.APPEND_AND_SPEAK, decide(event("tianshu:death", "死了"), START).action());

        AXEventDecision second = decide(event("tianshu:on_fire", "着火了"), START + 30_000L);

        assertEquals(AXEventTier.HIGH, second.tier());
        assertEquals(AXEventAction.APPEND_AND_BUFFER, second.action());
        assertFalse(second.speaksImmediately());
    }

    @Test
    void sameKindGapKeepsTheSentenceQueuedAfterTheGlobalGapElapses() {
        assertEquals(AXEventAction.APPEND_AND_SPEAK, decide(event("tianshu:death", "死了"), START).action());

        AXEventDecision tooSoon = decide(event("tianshu:death", "又死了"), START + 120_000L);
        AXEventDecision later = decide(event("tianshu:death", "第三次"), START + 301_000L);

        assertEquals(AXEventAction.APPEND_AND_BUFFER, tooSoon.action());
        assertEquals(AXEventAction.APPEND_AND_SPEAK, later.action());
    }

    @Test
    void sameKindGapIsSlottedPerSourceSoTwoProducersDoNotSilenceEachOther() {
        assertEquals(AXEventAction.APPEND_AND_SPEAK, decide(
                event("tianshu:death", "死了", AXEventPayload.of(), null, "module.one"), START).action());

        AXEventDecision otherSource = decide(
                event("tianshu:death", "也死了", AXEventPayload.of(), null, "module.two"), START + 61_000L);
        AXEventDecision sameSourceAgain = decide(
                event("tianshu:death", "又死了", AXEventPayload.of(), null, "module.one"), START + 122_000L);

        assertEquals(AXEventAction.APPEND_AND_SPEAK, otherSource.action());
        assertEquals(AXEventAction.APPEND_AND_BUFFER, sameSourceAgain.action());
    }

    @Test
    void theSourceNeverChangesTheDecisionItself() {
        AXEventDescriptor fromBuiltIn = event("tianshu:respawn", "在主世界重生点", AXEventPayload.of(), FOLD,
                "module.probe");
        AXEventDescriptor fromThirdParty = event("tianshu:respawn", "在主世界重生点", AXEventPayload.of(), FOLD,
                "plugin.harvest");

        AXEventDecision builtIn = new AXEventDispatcher().decide(fromBuiltIn, "sentence", START);
        AXEventDecision thirdParty = new AXEventDispatcher().decide(fromThirdParty, "sentence", START);

        assertEquals(builtIn, thirdParty);
    }

    @Test
    void foldedEventsComeBackWhenTheirWindowCloses() {
        String source = "module.probe";
        decide(event("tianshu:mob_killed", "击杀僵尸", AXEventPayload.of(), FOLD, source), START);
        decide(event("tianshu:mob_killed", "击杀僵尸", AXEventPayload.of(), FOLD, source), START + 1_000L);
        decide(event("tianshu:mob_killed", "击杀僵尸", AXEventPayload.of(), FOLD, source), START + 2_000L);

        assertTrue(dispatcher.drainDueAppends(START + 3_000L).isEmpty());
        List<AXEventAppend> appends = dispatcher.drainDueAppends(START + 32_000L);

        assertEquals(1, appends.size());
        AXEventAppend.Aggregated aggregated = (AXEventAppend.Aggregated) appends.get(0);
        assertEquals(3, aggregated.count());
        assertEquals("tianshu:mob_killed", aggregated.kind());
        assertEquals("folded 3", aggregated.text());
    }

    /**
     * These cases assert decisions, not wording, so the sentence handed to the dispatcher is a constant:
     * the layer never inspects it, it only stores it.
     */
    private AXEventDecision decide(AXEventDescriptor descriptor, long nowMillis) {
        return dispatcher.decide(descriptor, descriptor.kind(), nowMillis);
    }

    private static AXEventDescriptor event(String kind, String text) {
        return event(kind, text, AXEventPayload.of(), AXEventFold.NONE, PRODUCER);
    }

    private static AXEventDescriptor event(String kind, String text, AXEventPayload payload,
                                           AXEventFold fold) {
        return event(kind, text, payload, fold, PRODUCER);
    }

    private static AXEventDescriptor event(String kind, String text, AXEventPayload payload,
                                           AXEventFold fold, String sourceId) {
        return AXEventDescriptor.of(kind, sourceId, payload, AXEventText.of("test.line", text), fold);
    }
}
