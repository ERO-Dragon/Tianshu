package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.Arrays;

public final class AudioChunkPayload implements ITianshuPayload {
    private final byte[] audio;
    private final int sampleRate;
    private final boolean last;

    public AudioChunkPayload(byte[] audio, int sampleRate, boolean last) {
        this.audio = audio == null ? new byte[0] : Arrays.copyOf(audio, audio.length);
        this.sampleRate = sampleRate;
        this.last = last;
    }

    public byte[] audio() {
        return Arrays.copyOf(audio, audio.length);
    }

    public int sampleRate() {
        return sampleRate;
    }

    public boolean last() {
        return last;
    }
}
