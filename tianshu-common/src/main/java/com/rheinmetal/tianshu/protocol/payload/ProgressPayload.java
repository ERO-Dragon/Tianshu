package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record ProgressPayload(String targetEnvelopeId, double progress, String stage) implements ITianshuPayload {
}
