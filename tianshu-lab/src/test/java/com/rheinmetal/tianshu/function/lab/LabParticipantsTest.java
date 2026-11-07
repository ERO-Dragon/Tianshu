package com.rheinmetal.tianshu.function.lab;

import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantContractValidator;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimMode;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolCapabilityRegistration;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

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

    /**
     * The in-game failure this pins down: a RULES participant without a partner icon is rejected before it
     * ever reaches the registry, and the only later symptom is {@code NO_PARTICIPANT}.
     */
    @Test
    void theDescriptorsSatisfyTheArbitersRegistrationContract() {
        DialogueParticipantContractValidator validator = new DialogueParticipantContractValidator(this::registrations);

        assertTrue(validator.validate(LabParticipants.alpha("Lab Alpha")).accepted());
        assertTrue(validator.validate(LabParticipants.beta("Lab Beta")).accepted());
    }

    private List<ProtocolCapabilityRegistration> registrations(String capabilityId) {
        if (!LabProtocolAdapter.ALPHA_CAPABILITY.equals(capabilityId)
                && !LabProtocolAdapter.BETA_CAPABILITY.equals(capabilityId)) {
            return List.of();
        }
        return List.of(new ProtocolCapabilityRegistration(
                LabProtocolAdapter.MODULE_ID,
                new CapabilityDescriptor(
                        capabilityId,
                        PayloadType.of("DIALOGUE_DELIVERY"),
                        DialogueDeliveryPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.COMMAND),
                        Priority.LOW,
                        CompletionPolicy.MANUAL_COMPLETE)));
    }
}