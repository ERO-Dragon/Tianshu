package com.rheinmetal.tianshu.protocol.runtime;

public enum ProtocolTaskState {
    ACCEPTED,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    REJECTED
}
