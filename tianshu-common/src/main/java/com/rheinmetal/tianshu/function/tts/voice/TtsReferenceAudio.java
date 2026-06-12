package com.rheinmetal.tianshu.function.tts.voice;

import java.util.Arrays;

public record TtsReferenceAudio(float[] samples, int sampleRate) {
    public TtsReferenceAudio {
        samples = samples == null ? new float[0] : Arrays.copyOf(samples, samples.length);
        sampleRate = Math.max(1, sampleRate);
    }

    @Override
    public float[] samples() {
        return Arrays.copyOf(samples, samples.length);
    }
}
