package com.rheinmetal.tianshu.event;

public class TtsPlaybackEndEvent extends TianshuEvent {
    private final String source;

    public TtsPlaybackEndEvent(String source) {
        this(source, 0L);
    }

    public TtsPlaybackEndEvent(String source, long sessionId) {
        super(sessionId);
        this.source = source;
    }

    public String getSource() {
        return source;
    }
}
