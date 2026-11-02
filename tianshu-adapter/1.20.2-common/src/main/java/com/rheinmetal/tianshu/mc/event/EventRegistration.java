package com.rheinmetal.tianshu.mc.event;

import java.util.Objects;

/** Closeable ownership handle for one NeoForge event listener object. */
public final class EventRegistration implements AutoCloseable {
    private Runnable unregisterAction;

    private EventRegistration(Runnable unregisterAction) {
        this.unregisterAction = unregisterAction;
    }

    public static EventRegistration register(Runnable registerAction, Runnable unregisterAction) {
        Objects.requireNonNull(registerAction, "registerAction").run();
        return new EventRegistration(Objects.requireNonNull(unregisterAction, "unregisterAction"));
    }

    @Override
    public synchronized void close() {
        Runnable action = unregisterAction;
        if (action == null) {
            return;
        }
        unregisterAction = null;
        action.run();
    }
}
