package com.rheinmetal.tianshu.event;

public class AsrPartialTextEvent extends TianshuEvent {
    private final String text;

    public AsrPartialTextEvent(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
}
