package com.rheinmetal.tianshu.client.runtime;

import com.rheinmetal.tianshu.api.LogSink;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TianshuClientRuntimeLifecycleTest {

    /**
     * One counter per released resource.
     *
     * <p>They used to share one, which is why "the audio device was never shut down" could not be seen: the total
     * matched expectations as long as *some* three things ran.</p>
     */
    private static final class Releases {
        final AtomicInteger capture = new AtomicInteger();
        final AtomicInteger audio = new AtomicInteger();
        final AtomicInteger diagnostics = new AtomicInteger();
        final AtomicInteger index = new AtomicInteger();

        /** Every client-side resource released exactly once. */
        void assertAllReleasedOnce() {
            assertEquals(1, audio.get(), "the audio device must be released with the client");
            assertEquals(1, diagnostics.get(), "the diagnostic sink must be released");
            assertEquals(1, index.get(), "the index must be released");
        }
    }

    @Test
    void duplicateWorldStartAndStopAreIdempotent() {
        FakeCore core = new FakeCore();
        Releases releases = new Releases();
        TianshuClientRuntime runtime = runtime(core, releases);

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
        assertEquals(1, releases.capture.get());
        assertEquals(ClientSessionState.CLIENT_READY, runtime.state());
        assertEquals(0, releases.audio.get(), "leaving a world must not close the client's audio device");
    }

    @Test
    void staleStartCompletionCannotReviveStoppedWorld() {
        FakeCore core = new FakeCore();
        TianshuClientRuntime runtime = runtime(core, new Releases());

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
        TianshuClientRuntime runtime = runtime(core, new Releases());

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
    void shutdownIsIdempotentAndReleasesEachResourceOnce() {
        FakeCore core = new FakeCore();
        Releases releases = new Releases();
        TianshuClientRuntime runtime = runtime(core, releases);

        runtime.startClient();
        runtime.shutdown();
        runtime.shutdown();
        runtime.startWorldSession();

        assertEquals(1, core.destroys.get());
        releases.assertAllReleasedOnce();
        assertEquals(ClientSessionState.SHUTDOWN, runtime.state());
    }

    @Test
    void failedWorldStartCanBeRetried() {
        FakeCore core = new FakeCore();
        TianshuClientRuntime runtime = runtime(core, new Releases());

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
    void aClientThatCouldNotInstallItselfNeverOpensAWorld() {
        FakeCore core = new FakeCore();
        TianshuClientRuntime runtime = runtime(core, new Releases(), () -> {
            throw new IllegalStateException("no client resources");
        });

        runtime.startClient();
        assertEquals(ClientSessionState.CLIENT_FAILED, runtime.state());

        runtime.startWorldSession();

        assertEquals(0, core.starts.get(),
                "a session that failed to install must not hand a half-built client to the world");
        assertEquals(ClientSessionState.CLIENT_FAILED, runtime.state());
    }

    @Test
    void shutdownStillReleasesAfterAFailedInstallation() {
        FakeCore core = new FakeCore();
        Releases releases = new Releases();
        TianshuClientRuntime runtime = runtime(core, releases, () -> {
            throw new IllegalStateException("no client resources");
        });

        runtime.startClient();
        runtime.shutdown();

        assertEquals(1, core.destroys.get());
        releases.assertAllReleasedOnce();
        assertEquals(ClientSessionState.SHUTDOWN, runtime.state());
    }

    private static TianshuClientRuntime runtime(FakeCore core, Releases releases) {
        return runtime(core, releases, () -> { });
    }

    private static TianshuClientRuntime runtime(FakeCore core, Releases releases, Runnable startClientResources) {
        return new TianshuClientRuntime(
                core,
                startClientResources,
                releases.capture::incrementAndGet,
                releases.audio::incrementAndGet,
                releases.diagnostics::incrementAndGet,
                releases.index::incrementAndGet,
                LogSink.NOOP,
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