package com.rheinmetal.tianshu.protocol.registry;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class CapabilityDescriptor {
    private final String capabilityId;
    private final PayloadType supportedPayloadType;
    private final Class<? extends ITianshuPayload> payloadClass;
    private final BrokerType requiredBrokerType;
    private final Set<PacketType> acceptedPacketTypes;
    private final Priority minAcceptedPriority;
    private final CompletionPolicy completionPolicy;

    public CapabilityDescriptor(String capabilityId, PayloadType supportedPayloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType requiredBrokerType, Set<PacketType> acceptedPacketTypes, Priority minAcceptedPriority) {
        this(capabilityId, supportedPayloadType, payloadClass, requiredBrokerType, acceptedPacketTypes, minAcceptedPriority, CompletionPolicy.AUTO_COMPLETE_ON_RETURN);
    }

    public CapabilityDescriptor(String capabilityId, PayloadType supportedPayloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType requiredBrokerType, Set<PacketType> acceptedPacketTypes, Priority minAcceptedPriority, CompletionPolicy completionPolicy) {
        if (capabilityId == null || capabilityId.isBlank()) {
            throw new IllegalArgumentException("capabilityId cannot be blank");
        }
        this.capabilityId = capabilityId;
        this.supportedPayloadType = Objects.requireNonNull(supportedPayloadType, "supportedPayloadType");
        this.payloadClass = Objects.requireNonNull(payloadClass, "payloadClass");
        this.requiredBrokerType = Objects.requireNonNull(requiredBrokerType, "requiredBrokerType");
        this.acceptedPacketTypes = acceptedPacketTypes == null || acceptedPacketTypes.isEmpty() ? Collections.unmodifiableSet(EnumSet.allOf(PacketType.class)) : Collections.unmodifiableSet(EnumSet.copyOf(acceptedPacketTypes));
        this.minAcceptedPriority = Objects.requireNonNull(minAcceptedPriority, "minAcceptedPriority");
        this.completionPolicy = Objects.requireNonNull(completionPolicy, "completionPolicy");
    }

    public String capabilityId() { return capabilityId; }
    public PayloadType supportedPayloadType() { return supportedPayloadType; }
    public Class<? extends ITianshuPayload> payloadClass() { return payloadClass; }
    public BrokerType requiredBrokerType() { return requiredBrokerType; }
    public Set<PacketType> acceptedPacketTypes() { return acceptedPacketTypes; }
    public Priority minAcceptedPriority() { return minAcceptedPriority; }
    public CompletionPolicy completionPolicy() { return completionPolicy; }
}
