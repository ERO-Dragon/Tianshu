package com.rheinmetal.tianshu.function.tts.runtime;

import java.util.Optional;

public record TtsControlResult(
        boolean accepted,
        TtsControlAction action,
        int affectedSessions,
        TtsFailure failure,
        long occurredAtMillis
) {
    public TtsControlResult {
        action = action == null ? TtsControlAction.STOP_ALL : action;
        affectedSessions = Math.max(0, affectedSessions);
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }

    public static TtsControlResult accepted(TtsControlAction action, int affectedSessions) {
        return new TtsControlResult(true, action, affectedSessions, null, System.currentTimeMillis());
    }

    public static TtsControlResult rejected(TtsControlAction action, TtsFailure failure) {
        return new TtsControlResult(false, action, 0, failure == null ? TtsFailure.of(TtsFailureCode.UNKNOWN, "") : failure, System.currentTimeMillis());
    }

    public Optional<TtsFailure> failureResult() {
        return Optional.ofNullable(failure);
    }
}
