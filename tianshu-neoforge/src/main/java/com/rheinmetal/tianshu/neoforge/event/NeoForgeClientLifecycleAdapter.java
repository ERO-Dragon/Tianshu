package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.client.runtime.ClientRuntimeLifecycle;

import java.util.Objects;

public final class NeoForgeClientLifecycleAdapter {
    private final ClientRuntimeLifecycle runtime;

    public NeoForgeClientLifecycleAdapter(ClientRuntimeLifecycle runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public void onClientReady() {
        runtime.startClient();
    }

    public void onWorldLogin() {
        runtime.startWorldSession();
    }

    public void onWorldLogout() {
        runtime.stopWorldSession();
    }

    public void onClientTick() {
        runtime.tick();
    }

    public void onClientShutdown() {
        runtime.shutdown();
    }
}
