package com.rheinmetal.tianshu.event;

public class AsrFinalTextEvent extends TianshuEvent {
    private final String text;
    private final int turnId;

    public AsrFinalTextEvent(String text, int turnId) {
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
