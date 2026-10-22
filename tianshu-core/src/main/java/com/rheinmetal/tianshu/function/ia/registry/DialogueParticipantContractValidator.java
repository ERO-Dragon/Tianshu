package com.rheinmetal.tianshu.function.ia.registry;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ValidationResult;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolCapabilityRegistration;
import com.rheinmetal.tianshu.protocol.runtime.CapabilityRegistrationView;

import java.util.List;
import java.util.Objects;

public final class DialogueParticipantContractValidator {
    private final CapabilityRegistrationView registrationView;

    public DialogueParticipantContractValidator(CapabilityRegistrationView registrationView) {
        this.registrationView = Objects.requireNonNull(registrationView, "registrationView");
    }

    public ValidationResult validate(DialogueParticipantDescriptor descriptor) {
        if (descriptor == null) {
            return ValidationResult.reject("PARTICIPANT_DESCRIPTOR_MISSING", "Dialogue participant descriptor is required");
        }
        List<ProtocolCapabilityRegistration> registrations = registrationView.capabilityRegistrations(descriptor.routeCapability());
        if (registrations.isEmpty()) {
            return ValidationResult.reject("DIALOGUE_INPUT_CAPABILITY_NOT_FOUND", "Dialogue input capability is not registered: " + descriptor.routeCapability());
        }
        if (registrations.size() > 1) {
            return ValidationResult.reject("DIALOGUE_INPUT_CAPABILITY_AMBIGUOUS", "Dialogue input capability must be registered by exactly one module: " + descriptor.routeCapability());
        }

        ProtocolCapabilityRegistration registration = registrations.get(0);
        if (!descriptor.moduleId().equals(registration.moduleId())) {
            return ValidationResult.reject("DIALOGUE_INPUT_MODULE_MISMATCH", "Dialogue input capability belongs to another module");
        }

        CapabilityDescriptor capability = registration.descriptor();
        if (capability.supportedPayloadType() != PayloadType.DIALOGUE_DELIVERY) {
            return ValidationResult.reject("DIALOGUE_INPUT_PAYLOAD_TYPE_MISMATCH", "Dialogue input capability must accept DIALOGUE_DELIVERY payload type");
        }
        if (capability.payloadClass() != DialogueDeliveryPayload.class) {
            return ValidationResult.reject("DIALOGUE_INPUT_PAYLOAD_CLASS_MISMATCH", "Dialogue input capability must accept DialogueDeliveryPayload");
        }
        if (!capability.acceptedPacketTypes().contains(PacketType.COMMAND)) {
            return ValidationResult.reject("DIALOGUE_INPUT_PACKET_TYPE_REJECTED", "Dialogue input capability must accept COMMAND packets");
        }
        return ValidationResult.accept();
    }
}
