package com.rheinmetal.tianshu.function.tts.runtime;

@FunctionalInterface
public interface TtsAudioChunkConsumer {
    void accept(int chunkIndex, byte[] audio, boolean last);
}
