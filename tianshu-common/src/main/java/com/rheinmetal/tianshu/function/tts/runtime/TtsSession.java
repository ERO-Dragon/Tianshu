package com.rheinmetal.tianshu.function.tts.runtime;

public final class TtsSession {
    private final TtsRequest request;
    private final long createdAtMillis;
    private volatile TtsSessionState state;
    private volatile TtsFailure failure;

    public TtsSession(TtsRequest request) {
        this.request = request;
        this.createdAtMillis = System.currentTimeMillis();
        this.state = TtsSessionState.CREATED;
    }

    public TtsRequest request() {
        return request;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public TtsSessionState state() {
        return state;
    }

    public void transition(TtsSessionState state) {
        if (state == null || isTerminal()) {
            return;
        }
        this.state = state;
    }

    public void complete() {
        if (!isTerminal()) {
            this.state = TtsSessionState.COMPLETED;
        }
    }

    public void fail(TtsFailure failure) {
        if (isTerminal()) {
            return;
        }
        this.failure = failure == null ? TtsFailure.of(TtsFailureCode.UNKNOWN, "") : failure;
        this.state = TtsSessionState.FAILED;
    }

    public void cancel(String reason) {
        if (isTerminal()) {
            return;
        }
        this.failure = TtsFailure.of(TtsFailureCode.CANCELLED, reason == null ? "" : reason);
        this.state = TtsSessionState.CANCELLED;
    }

    public boolean isTerminal() {
        return state == TtsSessionState.COMPLETED || state == TtsSessionState.CANCELLED || state == TtsSessionState.FAILED;
    }

    public TtsFailure failure() {
        return failure;
    }

    public String failureReason() {
        return failure == null ? "" : failure.message();
    }
}
