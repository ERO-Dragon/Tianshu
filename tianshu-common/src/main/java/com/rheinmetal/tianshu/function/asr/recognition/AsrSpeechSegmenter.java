package com.rheinmetal.tianshu.function.asr.recognition;

import com.rheinmetal.tianshu.function.asr.audio.AsrSpeechActivitySnapshot;

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

    /** Resets the detector at a manual segment boundary while keeping the capture session active. */
    default void resetSegmentBoundary() {
    }

    default AsrSpeechActivitySnapshot activitySnapshot() {
        return AsrSpeechActivitySnapshot.inactive();
    }

    static AsrSpeechSegmenter disabled() {
        return DISABLED;
    }
}
