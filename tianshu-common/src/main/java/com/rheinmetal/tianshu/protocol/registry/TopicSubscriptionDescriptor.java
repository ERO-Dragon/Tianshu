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

public final class TopicSubscriptionDescriptor {
    private final String topicId;
    private final PayloadType supportedPayloadType;
    private final Class<? extends ITianshuPayload> payloadClass;
    private final BrokerType requiredBrokerType;
    private final Set<PacketType> acceptedPacketTypes;
    private final Priority minAcceptedPriority;
    private final CompletionPolicy completionPolicy;

    public TopicSubscriptionDescriptor(String topicId, PayloadType supportedPayloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType requiredBrokerType, Set<PacketType> acceptedPacketTypes, Priority minAcceptedPriority, CompletionPolicy completionPolicy) {
        if (topicId == null || topicId.isBlank()) {
            throw new IllegalArgumentException("topicId cannot be blank");
        }
        this.topicId = topicId;
        this.supportedPayloadType = Objects.requireNonNull(supportedPayloadType, "supportedPayloadType");
        this.payloadClass = Objects.requireNonNull(payloadClass, "payloadClass");
        this.requiredBrokerType = Objects.requireNonNull(requiredBrokerType, "requiredBrokerType");
        this.acceptedPacketTypes = acceptedPacketTypes == null || acceptedPacketTypes.isEmpty() ? Collections.unmodifiableSet(EnumSet.allOf(PacketType.class)) : Collections.unmodifiableSet(EnumSet.copyOf(acceptedPacketTypes));
        this.minAcceptedPriority = Objects.requireNonNull(minAcceptedPriority, "minAcceptedPriority");
        this.completionPolicy = Objects.requireNonNull(completionPolicy, "completionPolicy");
    }

    public String topicId() { return topicId; }
    public PayloadType supportedPayloadType() { return supportedPayloadType; }
    public Class<? extends ITianshuPayload> payloadClass() { return payloadClass; }
    public BrokerType requiredBrokerType() { return requiredBrokerType; }
    public Set<PacketType> acceptedPacketTypes() { return acceptedPacketTypes; }
    public Priority minAcceptedPriority() { return minAcceptedPriority; }
    public CompletionPolicy completionPolicy() { return completionPolicy; }

    public CapabilityDescriptor asCapabilityDescriptor() {
        return new CapabilityDescriptor(topicId, supportedPayloadType, payloadClass, requiredBrokerType, acceptedPacketTypes, minAcceptedPriority, completionPolicy);
    }
}
