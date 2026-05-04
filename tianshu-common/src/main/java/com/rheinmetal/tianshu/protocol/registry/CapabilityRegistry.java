package com.rheinmetal.tianshu.protocol.registry;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CapabilityRegistry {
    private final Map<String, List<HandlerRegistration>> capabilityHandlers = new ConcurrentHashMap<>();

    public void register(ModuleDescriptor moduleDescriptor, EnvelopeHandler handler) {
        for (CapabilityDescriptor capability : moduleDescriptor.capabilities()) {
            HandlerRegistration registration = new HandlerRegistration(moduleDescriptor, capability, handler);
            capabilityHandlers.compute(capability.capabilityId(), (key, existing) -> {
                List<HandlerRegistration> result = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
                result.add(registration);
                return Collections.unmodifiableList(result);
            });
        }
    }

    public List<HandlerRegistration> findCapability(String capabilityId) {
        return capabilityHandlers.getOrDefault(capabilityId, List.of());
    }

    public ValidationResult validate(TianshuEnvelope envelope, HandlerRegistration registration) {
        CapabilityDescriptor descriptor = registration.capabilityDescriptor();
        if (envelope.header().payloadType() != descriptor.supportedPayloadType()) {
            return ValidationResult.reject("PAYLOAD_TYPE_MISMATCH", "Expected " + descriptor.supportedPayloadType() + " but got " + envelope.header().payloadType());
        }
        if (envelope.payload() == null && descriptor.supportedPayloadType() != PayloadType.NONE) {
            return ValidationResult.reject("PAYLOAD_NULL", "Payload is required");
        }
        if (envelope.payload() != null && !descriptor.payloadClass().isInstance(envelope.payload())) {
            return ValidationResult.reject("PAYLOAD_CLASS_MISMATCH", "Payload class does not match descriptor");
        }
        if (!descriptor.acceptedPacketTypes().contains(envelope.header().packetType())) {
            return ValidationResult.reject("PACKET_TYPE_REJECTED", "Packet type rejected: " + envelope.header().packetType());
        }
        if (!envelope.header().priority().atLeast(descriptor.minAcceptedPriority())) {
            return ValidationResult.reject("PRIORITY_TOO_LOW", "Priority is lower than accepted minimum");
        }
        if (envelope.header().threadPolicy() == ThreadPolicy.MUST_MAIN && descriptor.requiredBrokerType() != BrokerType.MAIN_THREAD) {
            return ValidationResult.reject("THREAD_POLICY_MISMATCH", "MUST_MAIN envelope requires MAIN_THREAD broker");
        }
        if (descriptor.requiredBrokerType() == BrokerType.MAIN_THREAD && envelope.header().threadPolicy() != ThreadPolicy.MUST_MAIN && envelope.header().threadPolicy() != ThreadPolicy.ANY) {
            return ValidationResult.reject("THREAD_POLICY_MISMATCH", "MAIN_THREAD broker requires MUST_MAIN or ANY envelope");
        }
        return ValidationResult.accept();
    }

    public List<String> capabilityIds() {
        return new ArrayList<>(capabilityHandlers.keySet());
    }
}
