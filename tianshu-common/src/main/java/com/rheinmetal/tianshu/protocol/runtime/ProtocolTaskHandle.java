package com.rheinmetal.tianshu.protocol.runtime;

import java.util.Optional;

public interface ProtocolTaskHandle {
    String taskId();

    String moduleId();

    String envelopeId();

    ExecutionLane lane();

    ProtocolTaskState state();

    Optional<Throwable> failureCause();

    boolean cancel(String reason);

    boolean isDone();

    boolean isRunning();
}
