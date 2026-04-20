package com.rheinmetal.tianshu.event;

public class LlmChunkEvent extends TianshuEvent {
    private final String text;
    private final int turnId;

    public LlmChunkEvent(String text, int turnId) {
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
