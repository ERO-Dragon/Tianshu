package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.Arrays;

public final class TtsAudioPayload implements ITianshuPayload {
    private final String requestId;
    private final byte[] audio;
    private final int sampleRate;
    private final int channels;
    private final int chunkIndex;
    private final boolean last;

    public TtsAudioPayload(String requestId, byte[] audio, int sampleRate, int channels, int chunkIndex, boolean last) {
        this.requestId = requestId == null ? "" : requestId.trim();
        this.audio = audio == null ? new byte[0] : Arrays.copyOf(audio, audio.length);
        this.sampleRate = Math.max(1, sampleRate);
        this.channels = Math.max(1, channels);
        this.chunkIndex = Math.max(0, chunkIndex);
        this.last = last;
    }

    public String requestId() {
        return requestId;
    }

    public byte[] audio() {
        return Arrays.copyOf(audio, audio.length);
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public int chunkIndex() {
        return chunkIndex;
    }

    public boolean last() {
        return last;
    }
}
