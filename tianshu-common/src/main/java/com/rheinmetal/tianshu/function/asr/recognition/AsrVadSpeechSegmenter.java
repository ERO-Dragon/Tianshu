package com.rheinmetal.tianshu.function.asr.recognition;

import com.rheinmetal.tianshu.function.asr.audio.AsrSpeechActivityDetector;
import com.rheinmetal.tianshu.function.asr.audio.AsrSpeechActivityListener;
import com.rheinmetal.tianshu.function.asr.audio.AsrSpeechActivitySnapshot;

/** Reuses the ASR activity detector to produce one-shot segment boundaries. */
public final class AsrVadSpeechSegmenter implements AsrSpeechSegmenter {
    private final AsrSpeechActivityDetector detector;
    private boolean speechStarted;
    private boolean speechEnded;
    private long sessionId;

    public AsrVadSpeechSegmenter(AsrSpeechActivityListener activityListener) {
        this.detector = new AsrSpeechActivityDetector((speaking, sessionId, occurredAtMillis) -> {
            if (speaking) {
                speechStarted = true;
            } else {
                speechEnded = true;
            }
            if (activityListener != null) {
                activityListener.onSpeechActivity(speaking, sessionId, occurredAtMillis);
            }
        });
    }

    @Override
    public synchronized void start(long sessionId) {
        clearPendingTransitions();
        this.sessionId = sessionId;
        detector.start(sessionId);
    }

    @Override
    public synchronized Decision accept(byte[] pcmChunk) {
        detector.accept(pcmChunk);
        if (speechStarted) {
            speechStarted = false;
            return Decision.START_SEGMENT;
        }
        if (speechEnded) {
            speechEnded = false;
            return Decision.END_SEGMENT;
        }
        return Decision.CONTINUE;
    }

    @Override
    public synchronized void reset() {
        clearPendingTransitions();
        detector.stop();
        sessionId = 0L;
    }

    @Override
    public synchronized void resetSegmentBoundary() {
        clearPendingTransitions();
        if (sessionId > 0L) {
            detector.resetForSegmentBoundary(sessionId);
        } else {
            detector.stop();
        }
        clearPendingTransitions();
    }

    @Override
    public synchronized AsrSpeechActivitySnapshot activitySnapshot() {
        return detector.snapshot();
    }

    private void clearPendingTransitions() {
        speechStarted = false;
        speechEnded = false;
    }
}
