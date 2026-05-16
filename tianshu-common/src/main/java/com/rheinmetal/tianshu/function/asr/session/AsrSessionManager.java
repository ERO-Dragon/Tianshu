package com.rheinmetal.tianshu.function.asr.session;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class AsrSessionManager {
    private final AtomicInteger turnSequence = new AtomicInteger(0);
    private final AtomicLong activeRecognitionSession = new AtomicLong(0L);

    public long beginRecognitionSession(long sessionId) {
        activeRecognitionSession.set(sessionId);
        return sessionId;
    }

    public long activeRecognitionSession() {
        return activeRecognitionSession.get();
    }

    public boolean isActive(long sessionId) {
        return sessionId != 0L && activeRecognitionSession.get() == sessionId;
    }

    public int nextTurnId() {
        return turnSequence.incrementAndGet();
    }

    public void interrupt(long sessionId) {
        activeRecognitionSession.set(sessionId);
    }

    public void reset() {
        activeRecognitionSession.set(0L);
    }
}
