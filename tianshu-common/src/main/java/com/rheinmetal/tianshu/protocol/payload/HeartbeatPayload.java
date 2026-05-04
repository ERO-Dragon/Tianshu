package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record HeartbeatPayload(String targetEnvelopeId, long timestamp, String stage) implements ITianshuPayload {
}
