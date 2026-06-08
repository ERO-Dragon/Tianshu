package com.rheinmetal.tianshu.protocol;

import com.rheinmetal.tianshu.protocol.payload.CancelPayload;

import java.util.UUID;

public final class EnvelopeBuilder {
    private static final String RESPONSE_TARGET = "core.response";
    private static final String CANCEL_TARGET = "core.cancel";

    private String envelopeId;
    private String traceId;
    private String parentId;
    private String sourceId;
    private TargetMode targetMode = TargetMode.CAPABILITY;
    private String target;
    private DeliveryPolicy deliveryPolicy = DeliveryPolicy.WAIT_IN_QUEUE;
    private PacketType packetType = PacketType.EVENT;
    private PayloadType payloadType = PayloadType.CUSTOM;
    private AckPolicy ackPolicy = AckPolicy.NONE;
    private Priority priority = Priority.NORMAL;
    private ThreadPolicy threadPolicy = ThreadPolicy.ASYNC_WORKER;
    private long createdAt;
    private long deadline;
    private long expireAt;
    private CancellationScope cancellationScope = CancellationScope.SELF_ONLY;
    private FailurePolicy failurePolicy = FailurePolicy.REPORT_ONLY;
    private ITianshuPayload payload;

    public static EnvelopeBuilder create() {
        return new EnvelopeBuilder();
    }

    public static EnvelopeBuilder childOf(TianshuEnvelope parent) {
        EnvelopeBuilder builder = new EnvelopeBuilder();
        builder.traceId = parent.header().traceId();
        builder.parentId = parent.header().envelopeId();
        builder.priority = parent.header().priority();
        return builder;
    }

    public static EnvelopeBuilder commandToCapability(String sourceId, String capabilityId, PayloadType payloadType, ITianshuPayload payload) {
        return create()
            .sourceId(sourceId)
            .targetMode(TargetMode.CAPABILITY)
            .target(capabilityId)
            .packetType(PacketType.COMMAND)
            .payloadType(payloadType)
            .payload(payload);
    }

    public static EnvelopeBuilder requestCapability(String sourceId, String capabilityId, PayloadType payloadType, ITianshuPayload payload) {
        return create()
            .sourceId(sourceId)
            .targetMode(TargetMode.CAPABILITY)
            .target(capabilityId)
            .packetType(PacketType.REQUEST)
            .payloadType(payloadType)
            .ackPolicy(AckPolicy.EXPECT_SUCCESS_OR_FAILURE)
            .payload(payload);
    }

    public static EnvelopeBuilder eventTopic(String sourceId, String topicId, PayloadType payloadType, ITianshuPayload payload) {
        return create()
            .sourceId(sourceId)
            .targetMode(TargetMode.TOPIC)
            .target(topicId)
            .packetType(PacketType.EVENT)
            .payloadType(payloadType)
            .payload(payload);
    }

    public static EnvelopeBuilder responseTo(String sourceId, TianshuEnvelope parent, PayloadType payloadType, ITianshuPayload payload) {
        return childOf(parent)
            .sourceId(sourceId)
            .targetMode(TargetMode.CAPABILITY)
            .target(RESPONSE_TARGET)
            .packetType(PacketType.RESPONSE)
            .payloadType(payloadType)
            .ackPolicy(AckPolicy.NONE)
            .payload(payload);
    }

    public static EnvelopeBuilder cancelEnvelope(String sourceId, TianshuEnvelope targetEnvelope, String reasonCode, String message) {
        return childOf(targetEnvelope)
            .sourceId(sourceId)
            .targetMode(TargetMode.CAPABILITY)
            .target(CANCEL_TARGET)
            .packetType(PacketType.CANCEL)
            .payloadType(PayloadType.CANCEL)
            .priority(Priority.CRITICAL)
            .payload(new CancelPayload(targetEnvelope.envelopeId(), reasonCode, message));
    }

    public EnvelopeBuilder envelopeId(String value) { envelopeId = value; return this; }
    public EnvelopeBuilder traceId(String value) { traceId = value; return this; }
    public EnvelopeBuilder parentId(String value) { parentId = value; return this; }
    public EnvelopeBuilder sourceId(String value) { sourceId = value; return this; }
    public EnvelopeBuilder targetMode(TargetMode value) { targetMode = value; return this; }
    public EnvelopeBuilder target(String value) { target = value; return this; }
    public EnvelopeBuilder deliveryPolicy(DeliveryPolicy value) { deliveryPolicy = value; return this; }
    public EnvelopeBuilder packetType(PacketType value) { packetType = value; return this; }
    public EnvelopeBuilder payloadType(PayloadType value) { payloadType = value; return this; }
    public EnvelopeBuilder ackPolicy(AckPolicy value) { ackPolicy = value; return this; }
    public EnvelopeBuilder priority(Priority value) { priority = value; return this; }
    public EnvelopeBuilder threadPolicy(ThreadPolicy value) { threadPolicy = value; return this; }
    public EnvelopeBuilder createdAt(long value) { createdAt = value; return this; }
    public EnvelopeBuilder deadline(long value) { deadline = value; return this; }
    public EnvelopeBuilder expireAt(long value) { expireAt = value; return this; }
    public EnvelopeBuilder cancellationScope(CancellationScope value) { cancellationScope = value; return this; }
    public EnvelopeBuilder failurePolicy(FailurePolicy value) { failurePolicy = value; return this; }
    public EnvelopeBuilder payload(ITianshuPayload value) { payload = value; return this; }

    public TianshuEnvelope build() {
        long now = createdAt > 0 ? createdAt : System.currentTimeMillis();
        String eid = hasText(envelopeId) ? envelopeId : UUID.randomUUID().toString();
        String tid = hasText(traceId) ? traceId : eid;
        long effectiveDeadline = deadline > 0 ? deadline : now + 30_000L;
        long effectiveExpireAt = expireAt > 0 ? expireAt : Math.max(effectiveDeadline + 30_000L, now + 60_000L);
        EnvelopeHeader header = new EnvelopeHeader(eid, tid, parentId, sourceId, targetMode, target, deliveryPolicy, packetType, payloadType, ackPolicy, priority, threadPolicy, now, effectiveDeadline, effectiveExpireAt, cancellationScope, failurePolicy);
        return new TianshuEnvelope(header, payload);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
