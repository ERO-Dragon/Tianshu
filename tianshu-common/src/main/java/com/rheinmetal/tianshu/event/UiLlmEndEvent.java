package com.rheinmetal.tianshu.event;

public class UiLlmEndEvent extends TianshuEvent {
    private final int index;

    public UiLlmEndEvent(int index, long sessionId) {
        super(sessionId);
        this.index = index;
    }

    public int getIndex() {
        return index;
    }
}
