package com.rheinmetal.tianshu.event;

public class LlmEndEvent extends TianshuEvent {
    private final int turnId;
    private final boolean cancelled;
    private final String errorMessage;

    public LlmEndEvent(int turnId) {
        this(turnId, 0L, false, null);
    }

    public LlmEndEvent(int turnId, long sessionId) {
        this(turnId, sessionId, false, null);
    }

    public LlmEndEvent(int turnId, long sessionId, boolean cancelled, String errorMessage) {
        super(sessionId);
        this.turnId = turnId;
        this.cancelled = cancelled;
        this.errorMessage = errorMessage;
    }

    public int getTurnId() {
        return turnId;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean hasError() {
        return errorMessage != null && !errorMessage.isBlank();
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
