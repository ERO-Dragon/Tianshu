package com.rheinmetal.tianshu.function.ia.partner;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueHoldTier;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialoguePartnerTrackerTest {
    @Test
    void aWinningTierOpensALeaseThatExpiresAfterItsDuration() {
        DialoguePartnerTracker tracker = new DialoguePartnerTracker();

        tracker.grant(participant(DialogueHoldTier.TEN_SECONDS), 1_000L);

        assertTrue(tracker.leaseAlive(1_000L));
        assertEquals(11_000L, tracker.leaseUntilMillis(1_000L));
        assertEquals(10_000L, tracker.leaseDurationMillis(1_000L));
        assertTrue(tracker.leaseAlive(10_999L));
        assertFalse(tracker.leaseAlive(11_000L));
        assertEquals("", tracker.holderParticipantId(11_000L));
    }

    @Test
    void renewingAMovesTheDeadlineFromTheNewGrant() {
        DialoguePartnerTracker tracker = new DialoguePartnerTracker();
        DialogueParticipantDescriptor holder = participant(DialogueHoldTier.FIVE_SECONDS);

        tracker.grant(holder, 1_000L);
        tracker.grant(holder, 4_000L);

        assertEquals(9_000L, tracker.leaseUntilMillis(4_000L));
        assertTrue(tracker.leaseAlive(8_999L));
        assertFalse(tracker.leaseAlive(9_000L));
    }

    @Test
    void aZeroTierNeverHoldsTheTurn() {
        DialoguePartnerTracker tracker = new DialoguePartnerTracker();
        tracker.grant(participant(DialogueHoldTier.TEN_SECONDS), 1_000L);

        tracker.grant(participant(DialogueHoldTier.NONE), 2_000L);

        assertFalse(tracker.leaseAlive(2_000L));
        assertEquals("", tracker.holderParticipantId(2_000L));
    }

    @Test
    void clearDropsTheLeaseImmediately() {
        DialoguePartnerTracker tracker = new DialoguePartnerTracker();
        tracker.grant(participant(DialogueHoldTier.TWENTY_SECONDS), 1_000L);

        tracker.clear();

        assertFalse(tracker.leaseAlive(1_001L));
    }

    private static DialogueParticipantDescriptor participant(DialogueHoldTier tier) {
        return new DialogueParticipantDescriptor(
                "maid",
                "module.maid",
                "酒狐",
                10,
                DialogueClaimProfile.rules(java.util.List.of()),
                DialogueTriggerGroup.EMPTY,
                "MAID.DIALOGUE_INPUT",
                DialogueTurnProcessingPolicy.DEFAULT,
                "tianshu:textures/gui/partner/maid.png",
                tier
        );
    }
}