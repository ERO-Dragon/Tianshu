package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.DeadLetterPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

public record DeadLetterRecord(String envelopeId, String traceId, String sourceId, String target, String targetMode, String payloadType, String packetType, String errorCode, String reason, DeadLetterPolicy policy, long timestamp) {
    public static DeadLetterRecord from(TianshuEnvelope envelope, String errorCode, String reason, DeadLetterPolicy policy) {
        return new DeadLetterRecord(envelope.envelopeId(), envelope.traceId(), envelope.header().sourceId(), envelope.header().target(), envelope.header().targetMode().name(), envelope.header().payloadType().name(), envelope.header().packetType().name(), errorCode, reason, policy, System.currentTimeMillis());
    }
}
