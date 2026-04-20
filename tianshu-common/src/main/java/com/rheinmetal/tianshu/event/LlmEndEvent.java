package com.rheinmetal.tianshu.event;

public class LlmEndEvent extends TianshuEvent {
    private final int turnId;

    public LlmEndEvent(int turnId) {
        this.turnId = turnId;
    }

    public int getTurnId() {
        return turnId;
    }
}
