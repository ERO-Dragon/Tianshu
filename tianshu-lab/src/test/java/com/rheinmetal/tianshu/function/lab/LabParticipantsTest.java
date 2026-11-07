package com.rheinmetal.tianshu.function.lab;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimMode;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabParticipantsTest {

    @Test
    void ruleBasedParticipantsDeclareAWakeClaimAndAPartnerIcon() {
        DialogueParticipantDescriptor alpha = LabParticipants.alpha("Lab Alpha");

        assertEquals(LabProtocolAdapter.MODULE_ID, alpha.moduleId());
        assertEquals(LabProtocolAdapter.ALPHA_CAPABILITY, alpha.routeCapability());
        assertEquals(DialogueClaimMode.RULES, alpha.claimProfile().mode());
        assertEquals(30, alpha.priority());
        assertTrue(alpha.hasPartnerIcon(), "a rule-based participant must declare a partner icon");
        assertTrue(alpha.icon().matches("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+"), "the icon is a namespaced texture path");
        assertTrue(alpha.triggerGroup().wakeWords().contains("阿尔法"), "the wake word used in the test transcript");
        assertTrue(alpha.triggerGroup().wakeWords().contains("alpha"), "the English variant claims the same participant");
        assertTrue(
                alpha.claimProfile().rules().stream()
                        .flatMap(rule -> rule.conditions().stream())
                        .flatMap(condition -> condition.values().stream())
                        .toList()
                        .contains("阿尔法"),
                "the claim condition is what makes the arbiter pick this participant");
    }

    @Test
    void theTwoTestParticipantsAreDistinguishableByEverythingArbitrationUses() {
        DialogueParticipantDescriptor alpha = LabParticipants.alpha("Lab Alpha");
        DialogueParticipantDescriptor beta = LabParticipants.beta("Lab Beta");

        assertNotEquals(alpha.participantId(), beta.participantId());
        assertNotEquals(alpha.routeCapability(), beta.routeCapability(), "a delivery has to name its owner");
        assertTrue(alpha.priority() > beta.priority(), "priority is the tie-break the bench exercises");
        assertEquals(10, beta.priority());
    }
}