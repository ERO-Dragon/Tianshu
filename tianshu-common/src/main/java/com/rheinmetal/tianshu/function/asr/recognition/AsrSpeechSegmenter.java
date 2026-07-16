package com.rheinmetal.tianshu.function.asr.recognition;

public interface AsrSpeechSegmenter {
    AsrSpeechSegmenter DISABLED = new AsrSpeechSegmenter() {
    };

    enum Decision {
        CONTINUE,
        START_SEGMENT,
        END_SEGMENT

        ;

        public boolean startsSegment() {
            return this == START_SEGMENT;
        }

        public boolean endsSegment() {
            return this == END_SEGMENT;
        }
    }

    default void start(long sessionId) {
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
