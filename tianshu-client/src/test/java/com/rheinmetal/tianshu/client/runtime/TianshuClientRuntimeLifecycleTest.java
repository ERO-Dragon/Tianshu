package com.rheinmetal.tianshu.client.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TianshuClientRuntimeLifecycleTest {
    @Test
    void duplicateWorldStartAndStopAreIdempotent() {
        FakeCore core = new FakeCore();
        AtomicInteger releaseCapture = new AtomicInteger();
        TianshuClientRuntime runtime = runtime(core, releaseCapture, new AtomicInteger());

        runtime.startClient();
        runtime.startWorldSession();
        runtime.startWorldSession();
        assertEquals(1, core.starts.get());

        core.start.complete(true);
        assertEquals(ClientSessionState.WORLD_RUNNING, runtime.state());

        runtime.stopWorldSession();
        runtime.stopWorldSession();
        assertEquals(1, core.stops.get());
        core.stop.complete(null);
        assertEquals(1, releaseCapture.get());
        assertEquals(ClientSessionState.CLIENT_READY, runtime.state());
    }

    @Test
    void staleStartCompletionCannotReviveStoppedWorld() {
        FakeCore core = new FakeCore();
        TianshuClientRuntime runtime = runtime(core, new AtomicInteger(), new AtomicInteger());

        runtime.startClient();
        runtime.startWorldSession();
        runtime.stopWorldSession();
        core.start.complete(true);
        core.stop.complete(null);

        assertEquals(ClientSessionState.CLIENT_READY, runtime.state());
    }

    @Test
    void loginDuringWorldStopStartsTheNextSessionAfterStopCompletes() {
        FakeCore core = new FakeCore();
        TianshuClientRuntime runtime = runtime(core, new AtomicInteger(), new AtomicInteger());

        runtime.startClient();
        runtime.startWorldSession();
        core.start.complete(true);
        runtime.stopWorldSession();

        core.start = new CompletableFuture<>();
        runtime.startWorldSession();
        assertEquals(1, core.starts.get());

        core.stop.complete(null);
        assertEquals(2, core.starts.get());
        assertEquals(ClientSessionState.WORLD_STARTING, runtime.state());

        core.start.complete(true);
        assertEquals(ClientSessionState.WORLD_RUNNING, runtime.state());
    }

    @Test
    void shutdownIsIdempotentAndRejectsLaterLifecycleEvents() {
        FakeCore core = new FakeCore();
        AtomicInteger closedResources = new AtomicInteger();
        TianshuClientRuntime runtime = runtime(core, new AtomicInteger(), closedResources);

        runtime.startClient();
        runtime.shutdown();
        runtime.shutdown();
        runtime.startWorldSession();

        assertEquals(1, core.destroys.get());
        assertEquals(3, closedResources.get());
        assertEquals(ClientSessionState.SHUTDOWN, runtime.state());
    }

    @Test
    void failedWorldStartCanBeRetried() {
        FakeCore core = new FakeCore();
        TianshuClientRuntime runtime = runtime(core, new AtomicInteger(), new AtomicInteger());

        runtime.startClient();
        runtime.startWorldSession();
        core.start.complete(false);
        assertEquals(ClientSessionState.CLIENT_READY, runtime.state());

        core.start = new CompletableFuture<>();
        runtime.startWorldSession();
        core.start.complete(true);

        assertEquals(2, core.starts.get());
        assertEquals(ClientSessionState.WORLD_RUNNING, runtime.state());
    }

    @Test
    void presenceWorldSessionFollowsAcceptedCoreLifecycle() {
        FakeCore core = new FakeCore();
        AtomicInteger presenceStarts = new AtomicInteger();
        AtomicInteger presenceStops = new AtomicInteger();
        TianshuClientRuntime runtime = new TianshuClientRuntime(
                core,
                () -> { },
                () -> { },
                () -> { },
                () -> { },
                () -> { },
                () -> { },
                presenceStarts::incrementAndGet,
                presenceStops::incrementAndGet,
                () -> { },
                ignored -> { }
        );

        runtime.startClient();
        runtime.startWorldSession();
        assertEquals(0, presenceStarts.get());

        core.start.complete(true);
        assertEquals(1, presenceStarts.get());

        runtime.stopWorldSession();
        assertEquals(1, presenceStops.get());
    }

    private static TianshuClientRuntime runtime(FakeCore core, AtomicInteger releaseCapture, AtomicInteger closedResources) {
        return new TianshuClientRuntime(
                core,
                () -> { },
                releaseCapture::incrementAndGet,
                closedResources::incrementAndGet,
                closedResources::incrementAndGet,
                closedResources::incrementAndGet,
                () -> { },
                () -> { },
                ignored -> { }
        );
    }

    private static final class FakeCore implements TianshuClientRuntime.CoreLifecycle {
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger stops = new AtomicInteger();
        private final AtomicInteger destroys = new AtomicInteger();
        private CompletableFuture<Boolean> start = new CompletableFuture<>();
        private final CompletableFuture<Void> stop = new CompletableFuture<>();

        @Override
        public CompletableFuture<Boolean> start() {
            starts.incrementAndGet();
            return start;
        }

        @Override
        public CompletableFuture<Void> stop() {
            stops.incrementAndGet();
            return stop;
        }

        @Override
        public CompletableFuture<Void> destroy() {
            destroys.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }
}
