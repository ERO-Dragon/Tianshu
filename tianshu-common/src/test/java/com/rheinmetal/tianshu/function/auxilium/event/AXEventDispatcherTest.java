package com.rheinmetal.tianshu.function.auxilium.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXEventDispatcherTest {
    private static final long START = 1_000_000L;

    private final AXEventDispatcher dispatcher = new AXEventDispatcher();

    @Test
    void unknownKindFallsBackToLowAndOnlyAppends() {
        AXEventDecision decision = dispatcher.decide(event("mymod:ritual_complete", "仪式完成"), START);

        assertEquals(AXEventTier.LOW, decision.tier());
        assertFalse(decision.blacklisted());
        assertEquals(AXEventAction.APPEND_ONLY, decision.action());
        assertNull(decision.speech());
    }

    @Test
    void highTierAppendsAndSpeaksImmediately() {
        AXEventDecision decision = dispatcher.decide(event("tianshu:death", "被岩浆怪杀死"), START);

        assertEquals(AXEventTier.HIGH, decision.tier());
        assertEquals(AXEventAction.APPEND_AND_SPEAK, decision.action());
        assertTrue(decision.speaksImmediately());
        assertEquals("被岩浆怪杀死", decision.appendText());
        assertFalse(decision.folded());
        assertNotNull(decision.speech());
        assertEquals("tianshu.ax.event.speech.tianshu.death", decision.speech().key());
        assertEquals("被岩浆怪杀死", decision.speech().args().get(0));
    }

    @Test
    void highTierWithSuppressOnlyAppends() {
        AXEventDescriptor descriptor = new AXEventDescriptor(
                "tianshu:death", null, true, AXEventPayload.of("被岩浆怪杀死"));

        AXEventDecision decision = dispatcher.decide(descriptor, START);

        assertEquals(AXEventTier.HIGH, decision.tier());
        assertTrue(decision.blacklisted());
        assertEquals(AXEventAction.APPEND_ONLY, decision.action());
        assertNull(decision.speech());
        assertEquals("被岩浆怪杀死", decision.appendText());
    }

    @Test
    void midTierAppendsAndBuffersItsSpeech() {
        AXEventDecision decision = dispatcher.decide(event("tianshu:respawn", "在主世界重生点"), START);

        assertEquals(AXEventTier.MID, decision.tier());
        assertEquals(AXEventAction.APPEND_AND_BUFFER, decision.action());
        assertFalse(decision.speaksImmediately());
        assertNotNull(decision.speech());
        assertTrue(decision.folded());
        assertNull(decision.appendText());
    }

    @Test
    void midTierBlacklistedOnlyAppends() {
        AXEventDescriptor descriptor = new AXEventDescriptor(
                "tianshu:respawn", null, true, AXEventPayload.of("在主世界重生点"));

        AXEventDecision decision = dispatcher.decide(descriptor, START);

        assertEquals(AXEventAction.APPEND_ONLY, decision.action());
        assertNull(decision.speech());
        assertTrue(decision.folded());
    }

    @Test
    void lowTierWithoutBlacklistOnlyAppends() {
        AXEventDecision decision = dispatcher.decide(
                event("tianshu:health_change", "失去 3 心", 15), START);

        assertEquals(AXEventTier.LOW, decision.tier());
        assertEquals(AXEventAction.APPEND_ONLY, decision.action());
        assertNull(decision.speech());
        assertTrue(decision.folded());
        assertTrue(decision.appends());
    }

    @Test
    void lowTierWithBuiltInBlacklistIsDroppedCompletely() {
        AXEventDecision decision = dispatcher.decide(event("tianshu:block_break", "挖掉圆石"), START);

        assertEquals(AXEventAction.DROP, decision.action());
        assertFalse(decision.appends());
        assertNull(decision.appendText());
        assertNull(decision.speech());
        assertFalse(decision.folded());
        assertTrue(dispatcher.flushAppends().isEmpty());
    }

    @Test
    void suppressOverridesABuiltInNonBlacklistedKind() {
        AXEventDescriptor descriptor = new AXEventDescriptor(
                "tianshu:block_place", null, false, AXEventPayload.of("放下工作台"));
        AXEventDescriptor suppressed = new AXEventDescriptor(
                "tianshu:health_critical", null, true, AXEventPayload.of("血量 2 心"));

        assertEquals(AXEventAction.DROP, dispatcher.decide(descriptor, START).action());
        AXEventDecision decision = dispatcher.decide(suppressed, START);
        assertTrue(decision.blacklisted());
        assertEquals(AXEventAction.APPEND_ONLY, decision.action());
    }

    @Test
    void declaredTierWinsOverTheBuiltInTable() {
        AXEventDescriptor promoted = new AXEventDescriptor(
                "tianshu:respawn", AXEventTier.HIGH, false, AXEventPayload.of("在主世界重生点"));
        AXEventDescriptor promotedSilentKind = new AXEventDescriptor(
                "tianshu:chat_message", AXEventTier.HIGH, false, AXEventPayload.of("小明：借我点铁"));

        AXEventDecision decision = dispatcher.decide(promoted, START);
        AXEventDecision silentKind = dispatcher.decide(promotedSilentKind, START + 400_000L);

        assertEquals(AXEventTier.HIGH, decision.tier());
        assertEquals(AXEventAction.APPEND_AND_SPEAK, decision.action());
        assertEquals("tianshu.ax.event.speech.tianshu.respawn", decision.speech().key());
        assertEquals(AXEventAction.APPEND_AND_SPEAK, silentKind.action());
        assertEquals("tianshu.ax.event.speech.tianshu.chat_message", silentKind.speech().key());
    }

    @Test
    void globalGapTurnsASecondHighKindIntoABufferedSpeech() {
        assertEquals(AXEventAction.APPEND_AND_SPEAK, dispatcher.decide(event("tianshu:death", "死了"), START).action());

        AXEventDecision second = dispatcher.decide(event("tianshu:on_fire", "着火了"), START + 30_000L);

        assertEquals(AXEventTier.HIGH, second.tier());
        assertEquals(AXEventAction.APPEND_AND_BUFFER, second.action());
        assertFalse(second.speaksImmediately());
        assertNotNull(second.speech());
        assertEquals("着火了", second.appendText());
        assertFalse(second.folded());
    }

    @Test
    void sameKindGapKeepsTheSentenceQueuedAfterTheGlobalGapElapses() {
        assertEquals(AXEventAction.APPEND_AND_SPEAK, dispatcher.decide(event("tianshu:death", "死了"), START).action());

        AXEventDecision tooSoon = dispatcher.decide(event("tianshu:death", "又死了"), START + 120_000L);
        AXEventDecision later = dispatcher.decide(event("tianshu:death", "第三次"), START + 301_000L);

        assertEquals(AXEventAction.APPEND_AND_BUFFER, tooSoon.action());
        assertEquals(AXEventAction.APPEND_AND_SPEAK, later.action());
    }

    @Test
    void speechIsGrantedAgainOnceBothGapsAreOpen() {
        assertEquals(AXEventAction.APPEND_AND_SPEAK, dispatcher.decide(event("tianshu:death", "死了"), START).action());
        assertEquals(AXEventAction.APPEND_AND_BUFFER,
                dispatcher.decide(event("tianshu:death", "又死了"), START + 60_000L).action());
        assertEquals(AXEventAction.APPEND_AND_SPEAK,
                dispatcher.decide(event("tianshu:on_fire", "着火了"), START + 361_000L).action());
    }

    @Test
    void foldedEventsComeBackWhenTheirWindowCloses() {
        dispatcher.decide(event("tianshu:mob_killed", "击杀僵尸"), START);
        dispatcher.decide(event("tianshu:mob_killed", "击杀僵尸"), START + 1_000L);
        dispatcher.decide(event("tianshu:mob_killed", "击杀僵尸"), START + 2_000L);

        assertTrue(dispatcher.drainDueAppends(START + 3_000L).isEmpty());
        List<AXEventAppend> appends = dispatcher.drainDueAppends(START + 32_000L);

        assertEquals(1, appends.size());
        assertEquals(3, ((AXEventAppend.Aggregated) appends.get(0)).count());
    }

    private static AXEventDescriptor event(String kind, String text) {
        return AXEventDescriptor.of(kind, AXEventPayload.of(text));
    }

    private static AXEventDescriptor event(String kind, String text, Integer magnitude) {
        return AXEventDescriptor.of(kind, AXEventPayload.of(text, 1, magnitude));
    }
}
