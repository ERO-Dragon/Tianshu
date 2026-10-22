package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record CancelPayload(String targetEnvelopeId, String reasonCode, String message) implements ITianshuPayload {
}
