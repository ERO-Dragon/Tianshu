package com.rheinmetal.tianshu.event;

public class TtsAudioEvent extends TianshuEvent {
    private final byte[] audio;
    private final int turnId;

    public TtsAudioEvent(byte[] audio, int turnId) {
        this.audio = audio;
        this.turnId = turnId;
    }

    public byte[] getAudio() {
        return audio;
    }

    public int getTurnId() {
        return turnId;
    }
}
