package com.rheinmetal.tianshu.function.ia.registry;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimMode;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ValidationResult;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolCapabilityRegistration;
import com.rheinmetal.tianshu.protocol.runtime.CapabilityRegistrationView;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class DialogueParticipantContractValidator {
    /** A namespaced texture path, matching how the client resolves resource locations. */
    private static final Pattern ICON_PATTERN = Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+");

    private final CapabilityRegistrationView registrationView;

    public DialogueParticipantContractValidator(CapabilityRegistrationView registrationView) {
        this.registrationView = Objects.requireNonNull(registrationView, "registrationView");
    }

    public ValidationResult validate(DialogueParticipantDescriptor descriptor) {
        if (descriptor == null) {
            return ValidationResult.reject("PARTICIPANT_DESCRIPTOR_MISSING", "Dialogue participant descriptor is required");
        }
        if (descriptor.claimProfile().mode() == DialogueClaimMode.RULES && !descriptor.hasPartnerIcon()) {
            return ValidationResult.reject("PARTICIPANT_ICON_MISSING", "A participant that can own a turn must declare a partner icon");
        }
        if (descriptor.factoryDefaultOwner() && !descriptor.canBeDefaultOwner()) {
            return ValidationResult.reject("PARTICIPANT_FACTORY_DEFAULT_NOT_AVAILABLE", "Only a participant that can own an unclaimed turn can be the out-of-box default owner");
        }
        if (descriptor.hasPartnerIcon() && !ICON_PATTERN.matcher(descriptor.icon()).matches()) {
            return ValidationResult.reject("PARTICIPANT_ICON_INVALID", "Participant icon must be a namespaced texture path: " + descriptor.icon());
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
