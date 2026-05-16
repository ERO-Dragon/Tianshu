package com.rheinmetal.tianshu.function.asr.audio;

@FunctionalInterface
public interface AudioFrameProcessor {
    byte[] process(byte[] audio);

    static AudioFrameProcessor identity() {
        return audio -> audio;
    }
}
