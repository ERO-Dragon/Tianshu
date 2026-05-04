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
}
