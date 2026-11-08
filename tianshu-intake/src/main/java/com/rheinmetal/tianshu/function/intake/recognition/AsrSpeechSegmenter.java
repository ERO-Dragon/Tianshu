package com.rheinmetal.tianshu.function.intake.recognition;

import com.rheinmetal.tianshu.function.intake.audio.AsrSpeechActivitySnapshot;

/**
 * Segment boundaries for continuous listening.
 *
 * <p><b>Sealed:</b> the current runtime only supports push-to-talk, and the controller installs
 * {@link #disabled()}; nothing in production consumes segment decisions. The interface and its VAD
 * implementation are kept as the extension point for re-enabling continuous input, and their only current
 * callers are the capture and recognition tests.</p>
 */
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
