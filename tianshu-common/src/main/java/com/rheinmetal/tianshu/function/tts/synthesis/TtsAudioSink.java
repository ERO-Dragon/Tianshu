package com.rheinmetal.tianshu.function.tts.synthesis;

@FunctionalInterface
public interface TtsAudioSink {
    void accept(byte[] audio);
}
