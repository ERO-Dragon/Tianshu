package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record StatusPayload(String targetEnvelopeId, EnvelopeStatus status, String reasonCode, String message) implements ITianshuPayload {
}
