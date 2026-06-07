package com.rheinmetal.tianshu.function.asr.recognition;

public interface AsrSpeechSegmenter {
    AsrSpeechSegmenter DISABLED = new AsrSpeechSegmenter() {
    };

    enum Decision {
        CONTINUE,
        END_SEGMENT
    }

    default Decision accept(byte[] pcmChunk) {
        return Decision.CONTINUE;
    }

    default void reset() {
    }

    static AsrSpeechSegmenter disabled() {
        return DISABLED;
    }
}
