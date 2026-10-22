package com.rheinmetal.tianshu.core.runtime;

import com.rheinmetal.tianshu.protocol.payload.RuntimeInterruptPayload;
import com.rheinmetal.tianshu.protocol.runtime.RuntimeInterruptPublisher;

import java.util.concurrent.atomic.AtomicLong;

public final class RuntimeInterruptionService {
    private final RuntimeInterruptPublisher interruptPublisher;
    private final AtomicLong sessionSeq = new AtomicLong(1L);
    private volatile long activeSessionId = sessionSeq.get();

    public RuntimeInterruptionService(RuntimeInterruptPublisher interruptPublisher) {
        this.interruptPublisher = interruptPublisher;
    }

    public long activeSessionId() {
        return activeSessionId;
    }

    public long interruptOngoingProcessing(RuntimeInterruptPayload.Reason reason, String detail) {
        long sessionId = sessionSeq.incrementAndGet();
        activeSessionId = sessionId;
        interruptPublisher.publishRuntimeInterrupt(sessionId, reason, detail == null ? "" : detail);
        return sessionId;
    }
}
