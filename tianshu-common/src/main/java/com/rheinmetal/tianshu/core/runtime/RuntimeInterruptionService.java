package com.rheinmetal.tianshu.core.runtime;

import com.rheinmetal.tianshu.event.InterruptEvent;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.protocol.payload.RuntimeInterruptPayload;
import com.rheinmetal.tianshu.protocol.runtime.RuntimeInterruptPublisher;

public final class RuntimeInterruptionService {
    private final TianshuEventBus eventBus;
    private final RuntimeInterruptPublisher interruptPublisher;

    public RuntimeInterruptionService(TianshuEventBus eventBus, RuntimeInterruptPublisher interruptPublisher) {
        this.eventBus = eventBus;
        this.interruptPublisher = interruptPublisher;
    }

    public long interruptOngoingProcessing(RuntimeInterruptPayload.Reason reason, String detail) {
        long sessionId = eventBus.beginNewSession();
        eventBus.publishEvent(new InterruptEvent(sessionId));
        interruptPublisher.publishRuntimeInterrupt(sessionId, reason, detail == null ? "" : detail);
        return sessionId;
    }
}
