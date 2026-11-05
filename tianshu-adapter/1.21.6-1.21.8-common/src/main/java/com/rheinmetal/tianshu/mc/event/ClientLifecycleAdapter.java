package com.rheinmetal.tianshu.mc.event;

import com.rheinmetal.tianshu.client.runtime.ClientRuntimeLifecycle;

import java.util.Objects;

public final class ClientLifecycleAdapter {
    private final ClientRuntimeLifecycle runtime;

    public ClientLifecycleAdapter(ClientRuntimeLifecycle runtime) {
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

    public void onClientShutdown() {
        runtime.shutdown();
    }
}
