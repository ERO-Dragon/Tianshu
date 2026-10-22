package com.rheinmetal.tianshu.function.intake.audio;

@FunctionalInterface
public interface NoiseSuppressorProvider {
    AudioFrameProcessor create();

    static NoiseSuppressorProvider unavailable() {
        return () -> null;
    }
}
