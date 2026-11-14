package com.rheinmetal.tianshu.protocol;

import java.util.Objects;

public final class EnvelopeHeader {
    private final String envelopeId;
    private final String traceId;
    private final String parentId;
    private final String sourceId;
    private final TargetMode targetMode;
    private final String target;
    private final DeliveryPolicy deliveryPolicy;
    private final PacketType packetType;
    private final PayloadType payloadType;
    private final AckPolicy ackPolicy;
    private final Priority priority;
    private final ThreadPolicy threadPolicy;
    private final long createdAt;
    private final long deadline;
    private final long expireAt;
    private final CancellationScope cancellationScope;
    private final FailurePolicy failurePolicy;

    public EnvelopeHeader(String envelopeId, String traceId, String parentId, String sourceId, TargetMode targetMode, String target, DeliveryPolicy deliveryPolicy, PacketType packetType, PayloadType payloadType, AckPolicy ackPolicy, Priority priority, ThreadPolicy threadPolicy, long createdAt, long deadline, long expireAt, CancellationScope cancellationScope, FailurePolicy failurePolicy) {
        this.envelopeId = requireText(envelopeId, "envelopeId");
        this.traceId = requireText(traceId, "traceId");
        this.parentId = blankToNull(parentId);
        this.sourceId = requireText(sourceId, "sourceId");
        this.targetMode = Objects.requireNonNull(targetMode, "targetMode");
        this.target = requireText(target, "target");
        this.deliveryPolicy = Objects.requireNonNull(deliveryPolicy, "deliveryPolicy");
        this.packetType = Objects.requireNonNull(packetType, "packetType");
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        this.ackPolicy = Objects.requireNonNull(ackPolicy, "ackPolicy");
        this.priority = Objects.requireNonNull(priority, "priority");
        this.threadPolicy = Objects.requireNonNull(threadPolicy, "threadPolicy");
        this.createdAt = createdAt;
        this.deadline = deadline;
        this.expireAt = expireAt;
        this.cancellationScope = Objects.requireNonNull(cancellationScope, "cancellationScope");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        if (expireAt > 0 && deadline > 0 && expireAt < deadline) {
            throw new IllegalArgumentException("expireAt cannot be earlier than deadline");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public String envelopeId() { return envelopeId; }
    public String traceId() { return traceId; }
    public String parentId() { return parentId; }
    public String sourceId() { return sourceId; }
    public TargetMode targetMode() { return targetMode; }
    public String target() { return target; }
    public DeliveryPolicy deliveryPolicy() { return deliveryPolicy; }
    public PacketType packetType() { return packetType; }
    public PayloadType payloadType() { return payloadType; }
    public AckPolicy ackPolicy() { return ackPolicy; }
    public Priority priority() { return priority; }
    public ThreadPolicy threadPolicy() { return threadPolicy; }
    public long createdAt() { return createdAt; }
    public long deadline() { return deadline; }
    public long expireAt() { return expireAt; }
    public CancellationScope cancellationScope() { return cancellationScope; }
    public FailurePolicy failurePolicy() { return failurePolicy; }

    public boolean isExpired(long now) {
        return expireAt > 0 && now >= expireAt;
    }

    public EnvelopeHeader withPacket(PacketType newPacketType, PayloadType newPayloadType) {
        return new EnvelopeHeader(envelopeId, traceId, parentId, sourceId, targetMode, target, deliveryPolicy, newPacketType, newPayloadType, ackPolicy, priority, threadPolicy, createdAt, deadline, expireAt, cancellationScope, failurePolicy);
    }

    public EnvelopeHeader withTarget(TargetMode newTargetMode, String newTarget) {
        return new EnvelopeHeader(envelopeId, traceId, parentId, sourceId, newTargetMode, newTarget, deliveryPolicy, packetType, payloadType, ackPolicy, priority, threadPolicy, createdAt, deadline, expireAt, cancellationScope, failurePolicy);
    }
}
