package com.rheinmetal.tianshu.api.tool;

/** A cooperative cancellation flag a tool may poll; cancellation remains best effort. */
@FunctionalInterface
public interface CancellationSignal {

    CancellationSignal NEVER = () -> false;

    boolean isCancelled();
}
