package com.rheinmetal.tianshu.neoforge.event;

import java.util.Objects;

/** Closeable ownership handle for one NeoForge event listener object. */
public final class NeoForgeEventRegistration implements AutoCloseable {
    private Runnable unregisterAction;

    private NeoForgeEventRegistration(Runnable unregisterAction) {
        this.unregisterAction = unregisterAction;
    }

    public static NeoForgeEventRegistration register(Runnable registerAction, Runnable unregisterAction) {
        Objects.requireNonNull(registerAction, "registerAction").run();
        return new NeoForgeEventRegistration(Objects.requireNonNull(unregisterAction, "unregisterAction"));
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
