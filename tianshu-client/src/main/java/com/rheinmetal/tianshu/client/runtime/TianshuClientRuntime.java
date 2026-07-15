package com.rheinmetal.tianshu.client.runtime;

import com.rheinmetal.tianshu.core.TianshuCoreManager;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class TianshuClientRuntime implements ClientRuntimeLifecycle {
    private final CoreLifecycle core;
    private final Runnable startClientResources;
    private final Runnable releaseCaptureHardware;
    private final Runnable shutdownAudio;
    private final Runnable closeDiagnostics;
    private final Runnable closeIndex;
    private final Runnable tickPresence;
    private final Runnable onWorldReady;
    private final Consumer<Throwable> onWorldFailure;
    private final AtomicLong generation = new AtomicLong();
    private final Object lifecycleMonitor = new Object();
    private volatile ClientSessionState state = ClientSessionState.NEW;
    private boolean worldSessionRequested;

    public TianshuClientRuntime(
            ClientRuntimeServices services,
            Runnable onWorldReady,
            Consumer<Throwable> onWorldFailure
    ) {
        this(
                new CoreManagerLifecycle(services.coreManager()),
                () -> services.namedObjectIndexManager().initializeAsync("client startup"),
                services.audioManager()::releaseCaptureHardware,
                services.audioManager()::shutdown,
                services.diagnosticRouter()::close,
                services.namedObjectIndexManager()::close,
                services.presenceRuntime()::tick,
                onWorldReady,
                onWorldFailure
        );
    }

    TianshuClientRuntime(
            CoreLifecycle core,
            Runnable startClientResources,
            Runnable releaseCaptureHardware,
            Runnable shutdownAudio,
            Runnable closeDiagnostics,
            Runnable closeIndex,
            Runnable tickPresence,
            Runnable onWorldReady,
            Consumer<Throwable> onWorldFailure
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.startClientResources = Objects.requireNonNull(startClientResources, "startClientResources");
        this.releaseCaptureHardware = Objects.requireNonNull(releaseCaptureHardware, "releaseCaptureHardware");
        this.shutdownAudio = Objects.requireNonNull(shutdownAudio, "shutdownAudio");
        this.closeDiagnostics = Objects.requireNonNull(closeDiagnostics, "closeDiagnostics");
        this.closeIndex = Objects.requireNonNull(closeIndex, "closeIndex");
        this.tickPresence = Objects.requireNonNull(tickPresence, "tickPresence");
        this.onWorldReady = onWorldReady == null ? () -> { } : onWorldReady;
        this.onWorldFailure = onWorldFailure == null ? ignored -> { } : onWorldFailure;
    }

    @Override
    public void startClient() {
        synchronized (lifecycleMonitor) {
            if (state != ClientSessionState.NEW) {
                return;
            }
            state = ClientSessionState.CLIENT_READY;
        }
        startClientResources.run();
    }

    @Override
    public void startWorldSession() {
        synchronized (lifecycleMonitor) {
            if (state == ClientSessionState.SHUTDOWN) {
                return;
            }
            worldSessionRequested = true;
        }
        startRequestedWorldSession();
    }

    private void startRequestedWorldSession() {
        long requestGeneration;
        synchronized (lifecycleMonitor) {
            if (state == ClientSessionState.NEW) {
                startClient();
            }
            if (!worldSessionRequested || state != ClientSessionState.CLIENT_READY) {
                return;
            }
            state = ClientSessionState.WORLD_STARTING;
            requestGeneration = generation.incrementAndGet();
        }
        core.start().whenComplete((running, failure) -> completeWorldStart(requestGeneration, running, failure));
    }

    @Override
    public void stopWorldSession() {
        long requestGeneration;
        synchronized (lifecycleMonitor) {
            worldSessionRequested = false;
            if (state != ClientSessionState.WORLD_STARTING && state != ClientSessionState.WORLD_RUNNING) {
                return;
            }
            state = ClientSessionState.WORLD_STOPPING;
            requestGeneration = generation.incrementAndGet();
        }
        core.stop().whenComplete((ignored, failure) -> completeWorldStop(requestGeneration, failure));
    }

    @Override
    public void tick() {
        if (state == ClientSessionState.WORLD_RUNNING) {
            tickPresence.run();
        }
    }

    @Override
    public void shutdown() {
        synchronized (lifecycleMonitor) {
            if (state == ClientSessionState.SHUTDOWN) {
                return;
            }
            state = ClientSessionState.SHUTDOWN;
            worldSessionRequested = false;
            generation.incrementAndGet();
        }
        try {
            core.destroy().join();
        } catch (CompletionException failure) {
            onWorldFailure.accept(failure.getCause() == null ? failure : failure.getCause());
        } finally {
            shutdownAudio.run();
            closeDiagnostics.run();
            closeIndex.run();
        }
    }

    public ClientSessionState state() {
        return state;
    }

    public long generation() {
        return generation.get();
    }

    private void completeWorldStart(long requestGeneration, Boolean running, Throwable failure) {
        synchronized (lifecycleMonitor) {
            if (requestGeneration != generation.get() || state != ClientSessionState.WORLD_STARTING) {
                return;
            }
            if (failure != null || !Boolean.TRUE.equals(running)) {
                state = ClientSessionState.CLIENT_READY;
            } else {
                state = ClientSessionState.WORLD_RUNNING;
            }
        }
        if (failure != null || !Boolean.TRUE.equals(running)) {
            onWorldFailure.accept(failure == null ? new IllegalStateException("CORE_SESSION_NOT_RUNNING") : failure);
            return;
        }
        onWorldReady.run();
    }

    private void completeWorldStop(long requestGeneration, Throwable failure) {
        releaseCaptureHardware.run();
        boolean restartRequested = false;
        synchronized (lifecycleMonitor) {
            if (requestGeneration == generation.get() && state == ClientSessionState.WORLD_STOPPING) {
                state = ClientSessionState.CLIENT_READY;
                restartRequested = worldSessionRequested;
            }
        }
        if (failure != null) {
            onWorldFailure.accept(failure);
        }
        if (restartRequested) {
            startRequestedWorldSession();
        }
    }

    interface CoreLifecycle {
        CompletableFuture<Boolean> start();
        CompletableFuture<Void> stop();
        CompletableFuture<Void> destroy();
    }

    private record CoreManagerLifecycle(TianshuCoreManager coreManager) implements CoreLifecycle {
        private CoreManagerLifecycle {
            Objects.requireNonNull(coreManager, "coreManager");
        }

        @Override
        public CompletableFuture<Boolean> start() {
            return coreManager.startRuntimeSession().thenApply(status -> status != null && status.coreRunning());
        }

        @Override
        public CompletableFuture<Void> stop() {
            return coreManager.stopRuntimeSession().thenApply(ignored -> null);
        }

        @Override
        public CompletableFuture<Void> destroy() {
            return coreManager.destroy().thenApply(ignored -> null);
        }
    }
}
