package com.rheinmetal.tianshu.client.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void worldSessionListenersOpenWithTheRequestSoLoadingNoticesAreNotDropped() {
        FakeCore core = new FakeCore();
        AtomicInteger sessionStarts = new AtomicInteger();
        AtomicInteger sessionStops = new AtomicInteger();
        TianshuClientRuntime runtime = runtimeWithSession(core, sessionStarts, sessionStops);

        runtime.startClient();
        runtime.startWorldSession();
        assertEquals(1, sessionStarts.get(),
                "modules publish their loading notices while the core prepares, so the session has to be open");

        core.start.complete(true);
        assertEquals(1, sessionStarts.get(), "an accepted start must not open a second session");

        runtime.stopWorldSession();
        assertEquals(1, sessionStops.get());
    }

    @Test
    void aFailedWorldStartClosesTheSessionItOpened() {
        FakeCore core = new FakeCore();
        AtomicInteger sessionStarts = new AtomicInteger();
        AtomicInteger sessionStops = new AtomicInteger();
        TianshuClientRuntime runtime = runtimeWithSession(core, sessionStarts, sessionStops);

        runtime.startClient();
        runtime.startWorldSession();
        assertEquals(1, sessionStarts.get());

        core.start.completeExceptionally(new IllegalStateException("boom"));

        assertEquals(1, sessionStops.get(), "the session that was opened with the request must not outlive a failed start");
        assertEquals(ClientSessionState.CLIENT_READY, runtime.state());
    }

    private static TianshuClientRuntime runtimeWithSession(
            FakeCore core,
            AtomicInteger sessionStarts,
            AtomicInteger sessionStops
    ) {
        WorldSessionListener listener = new WorldSessionListener() {
            @Override
            public void startWorldSession() {
                sessionStarts.incrementAndGet();
            }

            @Override
            public void stopWorldSession() {
                sessionStops.incrementAndGet();
            }
        };
        return new TianshuClientRuntime(
                core,
                () -> { },
                () -> { },
                () -> { },
                () -> { },
                () -> { },
                List.of(listener),
                () -> { },
                ignored -> { }
        );
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
