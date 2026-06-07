package com.rheinmetal.tianshu.function.asr.audio;

@FunctionalInterface
public interface NoiseSuppressorProvider {
    AudioFrameProcessor create();

    static NoiseSuppressorProvider unavailable() {
        return () -> null;
    }
}
