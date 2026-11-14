package com.rheinmetal.tianshu.api.tool;

import java.util.concurrent.atomic.AtomicBoolean;

/** The writable side of a cancellation signal, owned by the dispatcher for one in-flight call. */
public final class CancellationToken implements CancellationSignal {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }
}
