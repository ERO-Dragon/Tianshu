package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record ErrorPayload(String targetEnvelopeId, String errorCode, String message, boolean retryable) implements ITianshuPayload {
}
