package com.rheinmetal.tianshu.function.ia.registry;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.CapabilityRegistry;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ValidationResult;
import com.rheinmetal.tianshu.protocol.payload.TextPayload;
import com.rheinmetal.tianshu.protocol.runtime.CapabilityRegistrationView;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolCapabilityRegistration;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueParticipantContractValidatorTest {
    @Test
    void acceptsStandardDialogueDeliveryCapability() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registerCapability(registry, "module.maid", new CapabilityDescriptor(
                "MAID.DIALOGUE_INPUT",
                PayloadType.DIALOGUE_DELIVERY,
                DialogueDeliveryPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW
        ));

        ValidationResult result = new DialogueParticipantContractValidator(view(registry)).validate(descriptor("module.maid", "MAID.DIALOGUE_INPUT"));

        assertTrue(result.accepted());
    }

    @Test
    void rejectsPrivatePayloadCapability() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registerCapability(registry, "module.maid", new CapabilityDescriptor(
                "MAID.DIALOGUE_INPUT",
                PayloadType.TEXT,
                TextPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW
        ));

        ValidationResult result = new DialogueParticipantContractValidator(view(registry)).validate(descriptor("module.maid", "MAID.DIALOGUE_INPUT"));

        assertEquals("DIALOGUE_INPUT_PAYLOAD_TYPE_MISMATCH", result.code());
    }

    @Test
    void rejectsCapabilityOwnedByAnotherModule() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registerCapability(registry, "module.other", new CapabilityDescriptor(
                "MAID.DIALOGUE_INPUT",
                PayloadType.DIALOGUE_DELIVERY,
                DialogueDeliveryPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW
        ));

        ValidationResult result = new DialogueParticipantContractValidator(view(registry)).validate(descriptor("module.maid", "MAID.DIALOGUE_INPUT"));

        assertEquals("DIALOGUE_INPUT_MODULE_MISMATCH", result.code());
    }

    private static DialogueParticipantDescriptor descriptor(String moduleId, String capabilityId) {
        return new DialogueParticipantDescriptor(
                "maid.default",
                moduleId,
                "maid",
                10,
                com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile.DISABLED,
                com.rheinmetal.tianshu.protocol.dialogue.model.DialogueVoiceTriggerGroup.EMPTY,
                capabilityId,
                DialogueTurnProcessingPolicy.DEFAULT
        );
    }

    private static void registerCapability(CapabilityRegistry registry, String moduleId, CapabilityDescriptor capability) {
        registry.register(new ModuleDescriptor(
                moduleId,
                List.of(capability),
                ThreadPolicy.ANY,
                CancellationScope.SELF_ONLY,
                FailurePolicy.REPORT_ONLY,
                DeliveryPolicy.WAIT_IN_QUEUE,
                true,
                false,
                1,
                16
        ), (envelope, context) -> {});
    }

    private static CapabilityRegistrationView view(CapabilityRegistry registry) {
        return capabilityId -> registry.findCapability(capabilityId).stream()
                .map(registration -> new ProtocolCapabilityRegistration(
                        registration.moduleDescriptor().moduleId(),
                        registration.capabilityDescriptor()
                ))
                .toList();
    }
}
