package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.EnvelopeStatus;

public record EnvelopeTransition(String envelopeId, EnvelopeStatus status, String reasonCode, String message, long timestamp) {
}
