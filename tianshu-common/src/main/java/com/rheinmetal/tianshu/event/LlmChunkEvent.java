package com.rheinmetal.tianshu.event;

public class LlmChunkEvent extends TianshuEvent {
    private final String text;
    private final int turnId;

    public LlmChunkEvent(String text, int turnId) {
        this(text, turnId, 0L);
    }

    public LlmChunkEvent(String text, int turnId, long sessionId) {
        super(sessionId);
        this.text = text;
        this.turnId = turnId;
    }

    public String getText() {
        return text;
    }

    public int getTurnId() {
        return turnId;
    }
}
