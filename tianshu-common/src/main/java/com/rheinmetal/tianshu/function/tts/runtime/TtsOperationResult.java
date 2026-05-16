package com.rheinmetal.tianshu.function.tts.runtime;

import java.util.Optional;

public record TtsOperationResult(boolean accepted, String requestId, TtsFailure failure) {
    public TtsOperationResult {
        requestId = requestId == null ? "" : requestId.trim();
    }

    public static TtsOperationResult accepted(String requestId) {
        return new TtsOperationResult(true, requestId, null);
    }

    public static TtsOperationResult rejected(TtsFailure failure) {
        return new TtsOperationResult(false, "", failure == null ? TtsFailure.of(TtsFailureCode.UNKNOWN, "") : failure);
    }

    public Optional<TtsFailure> failureResult() {
        return Optional.ofNullable(failure);
    }
}
