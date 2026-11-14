package com.rheinmetal.tianshu.protocol.runtime;

import java.time.Duration;
import java.util.concurrent.Callable;

public interface ModuleExecutionAccess {
    ProtocolTaskHandle submit(ProtocolTaskSpec spec, Runnable task);

    <T> ProtocolTaskHandle submit(ProtocolTaskSpec spec, Callable<T> task);

    ProtocolTaskHandle schedule(ProtocolTaskSpec spec, Runnable task, Duration delay);
}
