package com.rheinmetal.tianshu.client.runtime;

/**
 * The client session lifecycle the platform drives.
 *
 * <p>There is no per-tick method: a module that needs a clock subscribes to {@code TickSignals} itself, so the
 * platform never calls into a module once per tick.</p>
 */
public interface ClientRuntimeLifecycle {
    void startClient();
    void startWorldSession();
    void stopWorldSession();
    void shutdown();
}
