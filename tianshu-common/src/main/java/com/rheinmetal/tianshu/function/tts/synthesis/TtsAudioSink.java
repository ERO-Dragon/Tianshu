package com.rheinmetal.tianshu.function.tts.synthesis;

@FunctionalInterface
public interface TtsAudioSink {
    void accept(byte[] audio);

    default TtsSynthesisMode preferredSynthesisMode() {
        return TtsSynthesisMode.FULL;
    }

    default TtsPlaybackBufferEstimate playbackBufferEstimate() {
        return TtsPlaybackBufferEstimate.empty();
    }

    default void reportSynthesisMetrics(TtsSynthesisMetrics metrics) {
    }
}
