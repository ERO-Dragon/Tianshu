package com.rheinmetal.tianshu.event;

public class UiAsrTextEvent extends TianshuEvent {
    private final String text;
    private final int index;

    public UiAsrTextEvent(String text, int index, long sessionId) {
        super(sessionId);
        this.text = text;
        this.index = index;
    }

    public String getText() {
        return text;
    }

    public int getIndex() {
        return index;
    }
}
