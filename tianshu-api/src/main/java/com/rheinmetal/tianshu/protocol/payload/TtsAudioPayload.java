package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.Arrays;

public final class TtsAudioPayload implements ITianshuPayload {
    private final String requestId;
    private final byte[] audio;
    private final int sampleRate;
    private final int channels;

    public TtsAudioPayload(String requestId, byte[] audio, int sampleRate, int channels) {
        this.requestId = requestId == null ? "" : requestId.trim();
        this.audio = audio == null ? new byte[0] : Arrays.copyOf(audio, audio.length);
        this.sampleRate = Math.max(1, sampleRate);
        this.channels = Math.max(1, channels);
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

}
