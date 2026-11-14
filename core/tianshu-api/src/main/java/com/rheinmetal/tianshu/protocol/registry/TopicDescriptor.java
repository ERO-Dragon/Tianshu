package com.rheinmetal.tianshu.protocol.registry;

import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.PayloadType;

import java.util.Objects;

public final class TopicDescriptor {
    private final String topicId;
    private final PayloadType payloadType;
    private final DeliveryPolicy deliveryPolicy;
    private final int stormLimitPerSecond;

    public TopicDescriptor(String topicId, PayloadType payloadType, DeliveryPolicy deliveryPolicy, int stormLimitPerSecond) {
        if (topicId == null || topicId.isBlank()) {
            throw new IllegalArgumentException("topicId cannot be blank");
        }
        this.topicId = topicId;
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        this.deliveryPolicy = Objects.requireNonNull(deliveryPolicy, "deliveryPolicy");
        this.stormLimitPerSecond = Math.max(1, stormLimitPerSecond);
    }

    public String topicId() { return topicId; }
    public PayloadType payloadType() { return payloadType; }
    public DeliveryPolicy deliveryPolicy() { return deliveryPolicy; }
    public int stormLimitPerSecond() { return stormLimitPerSecond; }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof TopicDescriptor other)) {
            return false;
        }
        return stormLimitPerSecond == other.stormLimitPerSecond
                && topicId.equals(other.topicId)
                && payloadType == other.payloadType
                && deliveryPolicy == other.deliveryPolicy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(topicId, payloadType, deliveryPolicy, stormLimitPerSecond);
    }
}
