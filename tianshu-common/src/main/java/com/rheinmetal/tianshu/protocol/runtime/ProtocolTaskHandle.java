package com.rheinmetal.tianshu.protocol.runtime;

public interface ProtocolTaskHandle {
    String taskId();

    String moduleId();

    String envelopeId();

    ExecutionLane lane();

    ProtocolTaskState state();

    boolean cancel(String reason);

    boolean isDone();

    boolean isRunning();
}
