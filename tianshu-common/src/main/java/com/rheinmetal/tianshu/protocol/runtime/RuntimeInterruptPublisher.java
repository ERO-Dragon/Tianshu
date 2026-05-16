package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.payload.RuntimeInterruptPayload;

public interface RuntimeInterruptPublisher {
    void publishRuntimeInterrupt(long sessionId, RuntimeInterruptPayload.Reason reason, String detail);
}
