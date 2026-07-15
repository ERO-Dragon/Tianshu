package com.rheinmetal.tianshu.client.runtime;

public interface ClientRuntimeLifecycle {
    void startClient();
    void startWorldSession();
    void stopWorldSession();
    void tick();
    void shutdown();
}
