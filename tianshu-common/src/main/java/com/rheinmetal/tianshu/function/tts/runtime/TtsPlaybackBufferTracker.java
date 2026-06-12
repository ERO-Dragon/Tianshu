package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsPlaybackBufferEstimate;

final class TtsPlaybackBufferTracker {
    private static final int PCM16_MONO_BYTES_PER_SAMPLE = 2;

    private long playbackStartMillis;
    private long submittedAudioMillis;
    private int sampleRate;

    synchronized void begin(int sampleRate) {
        this.sampleRate = Math.max(1, sampleRate);
        this.playbackStartMillis = System.currentTimeMillis();
        this.submittedAudioMillis = 0L;
    }

    synchronized void recordPcm16Mono(byte[] audio) {
        if (audio == null || audio.length == 0 || sampleRate <= 0) {
            return;
        }
        long samples = audio.length / PCM16_MONO_BYTES_PER_SAMPLE;
        submittedAudioMillis += samples * 1000L / sampleRate;
    }

    synchronized TtsPlaybackBufferEstimate estimate() {
        if (playbackStartMillis <= 0L || sampleRate <= 0) {
            return TtsPlaybackBufferEstimate.empty();
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - playbackStartMillis);
        long remaining = Math.max(0L, submittedAudioMillis - elapsed);
        return new TtsPlaybackBufferEstimate(remaining, submittedAudioMillis, elapsed);
    }

    synchronized void clear() {
        playbackStartMillis = 0L;
        submittedAudioMillis = 0L;
        sampleRate = 0;
    }
}
