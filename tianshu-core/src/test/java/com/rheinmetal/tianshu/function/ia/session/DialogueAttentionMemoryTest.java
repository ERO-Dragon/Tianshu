package com.rheinmetal.tianshu.function.ia.session;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueAttentionDecay;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaim;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimStrength;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueAttentionMemoryTest {
    @Test
    void normalFastAttentionDecaysBySecondsAndExpiresAtAxBaseline() {
        DialogueAttentionMemory memory = new DialogueAttentionMemory();
        DialogueParticipantDescriptor owner = descriptor("module.maid", "maid");

        memory.remember("player", owner, new DialogueClaim("maid", DialogueClaimStrength.NORMAL, DialogueAttentionDecay.FAST, 1, ""), 1_000L);

        assertTrue(memory.activeForPlayer("player", List.of(owner), 5_900L).isPresent());
        assertTrue(memory.activeForPlayer("player", List.of(owner), 6_000L).isEmpty());
        assertTrue(memory.playerIds().isEmpty());
    }

    @Test
    void attentionIsDroppedWhenOwnerParticipantIsNoLongerRegistered() {
        DialogueAttentionMemory memory = new DialogueAttentionMemory();
        DialogueParticipantDescriptor owner = descriptor("module.maid", "maid");

        memory.remember("player", owner, new DialogueClaim("maid", DialogueClaimStrength.STRONG, DialogueAttentionDecay.SLOW, 1, ""), 1_000L);

        assertTrue(memory.activeForPlayer("player", List.of(descriptor("module.ax", "ax")), 2_000L).isEmpty());
        assertEquals(List.of(), memory.playerIds());
    }

    private DialogueParticipantDescriptor descriptor(String moduleId, String participantId) {
        return new DialogueParticipantDescriptor(
                participantId,
                moduleId,
                participantId,
                1,
                DialogueClaimProfile.DISABLED,
                com.rheinmetal.tianshu.protocol.dialogue.model.DialogueVoiceTriggerGroup.EMPTY,
                "ROUTE." + participantId,
                DialogueTurnProcessingPolicy.DEFAULT
        );
    }
}
