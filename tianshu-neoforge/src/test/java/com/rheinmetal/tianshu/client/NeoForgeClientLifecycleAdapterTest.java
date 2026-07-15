package com.rheinmetal.tianshu.client;

import com.rheinmetal.tianshu.client.runtime.ClientRuntimeLifecycle;
import com.rheinmetal.tianshu.platform.NeoForgeClientLifecycleAdapter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NeoForgeClientLifecycleAdapterTest {
    @Test
    void forwardsEachPlatformEventToItsRuntimeOperation() {
        FakeRuntime runtime = new FakeRuntime();
        NeoForgeClientLifecycleAdapter adapter = new NeoForgeClientLifecycleAdapter(runtime);

        adapter.onClientReady();
        adapter.onWorldLogin();
        adapter.onClientTick();
        adapter.onWorldLogout();
        adapter.onClientShutdown();

        assertEquals(1, runtime.clientStarts.get());
        assertEquals(1, runtime.worldStarts.get());
        assertEquals(1, runtime.ticks.get());
        assertEquals(1, runtime.worldStops.get());
        assertEquals(1, runtime.shutdowns.get());
    }

    private static final class FakeRuntime implements ClientRuntimeLifecycle {
        private final AtomicInteger clientStarts = new AtomicInteger();
        private final AtomicInteger worldStarts = new AtomicInteger();
        private final AtomicInteger worldStops = new AtomicInteger();
        private final AtomicInteger ticks = new AtomicInteger();
        private final AtomicInteger shutdowns = new AtomicInteger();

        @Override public void startClient() { clientStarts.incrementAndGet(); }
        @Override public void startWorldSession() { worldStarts.incrementAndGet(); }
        @Override public void stopWorldSession() { worldStops.incrementAndGet(); }
        @Override public void tick() { ticks.incrementAndGet(); }
        @Override public void shutdown() { shutdowns.incrementAndGet(); }
    }
}
