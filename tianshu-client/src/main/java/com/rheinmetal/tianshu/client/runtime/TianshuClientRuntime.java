package com.rheinmetal.tianshu.client.runtime;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.client.tool.ClientTools;
import com.rheinmetal.tianshu.client.tool.ConnectionToolChannel;
import com.rheinmetal.tianshu.api.capability.skill.SkillFileProvider;
import com.rheinmetal.tianshu.client.capability.TextCapabilityProvider;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import java.nio.file.Path;
import com.rheinmetal.tianshu.core.TianshuCoreManager;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.io.IOException;

public final class TianshuClientRuntime implements ClientRuntimeLifecycle {
    /** How long shutdown waits for the kernel to tear its modules down before releasing client resources anyway. */
    private static final long DESTROY_TIMEOUT_MILLIS = 5_000L;
    /** Server tools may change after login; polling the reserved catalogue call keeps declarations current. */
    private static final long CATALOG_REFRESH_SECONDS = 60L;

    private final CoreLifecycle core;
    private final Runnable startClientResources;
    private final Runnable releaseCaptureHardware;
    private final Runnable shutdownAudio;
    private final Runnable closeDiagnostics;
    private final Runnable closeIndex;
    private final LogSink logs;
    private final Runnable onWorldReady;
    private final Consumer<Throwable> onWorldFailure;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile SkillFileProvider skills;
    private volatile SkillFileProvider builtInSkills;
    private volatile TextCapabilityProvider resources;
    private volatile TextCapabilityProvider prompts;
    private volatile TextCapabilityProvider builtInResources;
    private volatile TextCapabilityProvider builtInPrompts;
    private volatile ScheduledExecutorService catalogRefresher;
    private final Object stateLock = new Object();
    private volatile ClientSessionState state = ClientSessionState.NEW;
    private boolean worldSessionRequested;

    public TianshuClientRuntime(
            ClientRuntimeServices services,
            Runnable onWorldReady,
            Consumer<Throwable> onWorldFailure
    ) {
        this(
                new CoreManagerLifecycle(services.coreManager()),
                // The kernel owns no client resource of its own any more: module resources (the named-object
                // index among them) are built and released by the module that owns them.
                () -> { },
                services.audioManager()::releaseCaptureHardware,
                services.audioManager()::shutdown,
                services.diagnosticRouter()::close,
                () -> { },
                services.diagnosticRouter(),
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
            LogSink logs,
            Runnable onWorldReady,
            Consumer<Throwable> onWorldFailure
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.startClientResources = Objects.requireNonNull(startClientResources, "startClientResources");
        this.releaseCaptureHardware = Objects.requireNonNull(releaseCaptureHardware, "releaseCaptureHardware");
        this.shutdownAudio = Objects.requireNonNull(shutdownAudio, "shutdownAudio");
        this.closeDiagnostics = Objects.requireNonNull(closeDiagnostics, "closeDiagnostics");
        this.closeIndex = Objects.requireNonNull(closeIndex, "closeIndex");
        this.logs = logs == null ? LogSink.NOOP : logs;
        this.onWorldReady = onWorldReady == null ? () -> { } : onWorldReady;
        this.onWorldFailure = onWorldFailure == null ? ignored -> { } : onWorldFailure;
    }

    @Override
    public void startClient() {
        synchronized (stateLock) {
            if (state != ClientSessionState.NEW) {
                return;
            }
            state = ClientSessionState.INITIALIZING;
        }
        try {
            // Required, and deliberately not isolated: a duplicate tool registration or a transport that cannot be
            // installed leaves a session that would keep answering with a half-filled tool table. Failing here is the
            // honest outcome, and the state machine then refuses to open a world.
            ClientTools.register();
            ConnectionToolChannel.install();
        } catch (RuntimeException failure) {
            failInitialization(failure);
            return;
        }
        installContentProviders();
        installCatalogRefresher();
        try {
            startClientResources.run();
        } catch (RuntimeException failure) {
            failInitialization(failure);
            return;
        }
        boolean resumeWorld;
        synchronized (stateLock) {
            state = ClientSessionState.CLIENT_READY;
            resumeWorld = worldSessionRequested;
        }
        if (resumeWorld) {
            // A login that arrived while we were installing must not be lost: the request could not start then.
            startRequestedWorldSession();
        }
    }

    /**
     * Installs one optional piece, logging and continuing on failure.
     *
     * <p>These are content sources: with any of them missing the session still works, it just has
     * less to offer. Previously one exception during installation aborted the remaining ones <em>after</em> the state
     * machine had already claimed {@code CLIENT_READY}, so the world opened on a half-built client and every later
     * {@code startClient} returned early.</p>
     */
    private void installOptional(String what, Installation install) {
        try {
            install.run();
        } catch (IOException | RuntimeException failure) {
            logs.warn("client subsystem unavailable: " + what, failure);
        }
    }

    /** One installation step; a subsystem may fail with a checked I/O error (binding a port, reading a directory). */
    @FunctionalInterface
    private interface Installation {
        void run() throws IOException;
    }

    private void installContentProviders() {
        installOptional("built-in skills",
                () -> builtInSkills = SkillFileProvider.installClasspath("tianshu", "tianshu/skills"));
        installOptional("user skills",
                () -> skills = SkillFileProvider.install("user", Path.of("config", "Tianshu", "skills")));
        installOptional("built-in resources", () -> builtInResources = TextCapabilityProvider.installClasspath(
                CapabilityKind.RESOURCE, "tianshu", "tianshu/resources"));
        installOptional("built-in prompts", () -> builtInPrompts = TextCapabilityProvider.installClasspath(
                CapabilityKind.PROMPT, "tianshu", "tianshu/prompts"));
        installOptional("user resources", () -> resources = TextCapabilityProvider.install(
                CapabilityKind.RESOURCE, "user", Path.of("config", "Tianshu", "resources")));
        installOptional("user prompts", () -> prompts = TextCapabilityProvider.install(
                CapabilityKind.PROMPT, "user", Path.of("config", "Tianshu", "prompts")));
    }


    private void installCatalogRefresher() {
        ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tianshu-catalog");
            thread.setDaemon(true);
            return thread;
        });
        refresher.scheduleWithFixedDelay(TianshuClientRuntime::refreshCatalogQuietly,
                CATALOG_REFRESH_SECONDS, CATALOG_REFRESH_SECONDS, TimeUnit.SECONDS);
        catalogRefresher = refresher;
    }

    private void failInitialization(RuntimeException failure) {
        synchronized (stateLock) {
            state = ClientSessionState.CLIENT_FAILED;
        }
        logs.error("client initialization failed", failure);
        onWorldFailure.accept(failure);
    }

    @Override
    public void startWorldSession() {
        synchronized (stateLock) {
            if (state == ClientSessionState.SHUTDOWN) {
                return;
            }
            worldSessionRequested = true;
        }
        startRequestedWorldSession();
    }

    private void startRequestedWorldSession() {
        boolean needsInstall;
        synchronized (stateLock) {
            needsInstall = state == ClientSessionState.NEW;
        }
        if (needsInstall) {
            // Outside the monitor on purpose: installation can block on the network, and holding the
            // state lock for it would block shutdown() for as long as a dead server takes to answer.
            startClient();
        }
        long requestGeneration;
        synchronized (stateLock) {
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
        synchronized (stateLock) {
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
    public void shutdown() {
        synchronized (stateLock) {
            if (state == ClientSessionState.SHUTDOWN) {
                return;
            }
            state = ClientSessionState.SHUTDOWN;
            worldSessionRequested = false;
            generation.incrementAndGet();
        }
        // Our own poller first: it must not observe a session that is already going down.
        ScheduledExecutorService refresher = catalogRefresher;
        if (refresher != null) {
            refresher.shutdownNow();
            catalogRefresher = null;
        }
        CompletableFuture<Void> destroyed = core.destroy();
        try {
            // Bounded on purpose: this runs on the client thread during shutdown, and an unbounded join on a
            // module teardown that never returns would hang the process with no way out but killing it.
            destroyed.get(DESTROY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            releaseClientServices();
        } catch (TimeoutException timeout) {
            onWorldFailure.accept(timeout);
            // The kernel is still tearing its modules down. Releasing the diagnostic sink now would discard their
            // last lines, so the release waits for the teardown instead of racing it.
            destroyed.whenComplete((ignored, failure) -> releaseClientServices());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            onWorldFailure.accept(interrupted);
            destroyed.whenComplete((ignored, failure) -> releaseClientServices());
        } catch (ExecutionException failure) {
            onWorldFailure.accept(failure.getCause() == null ? failure : failure.getCause());
            releaseClientServices();
        }
    }

    /**
     * Releases what the client installed, once.
     *
     * <p>Runs only after the kernel has finished destroying its modules — the modules use these providers, so closing
     * them first was tearing the floor out from under the teardown. The order here is the reverse of installation, and
     * the diagnostic sink goes last so a provider that complains while closing is still heard.</p>
     */
    private void releaseClientServices() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        unload(builtInSkills);
        builtInSkills = null;
        unload(skills);
        skills = null;
        unload(builtInResources);
        builtInResources = null;
        unload(builtInPrompts);
        builtInPrompts = null;
        unload(resources);
        resources = null;
        unload(prompts);
        prompts = null;
        // The audio device is a client resource like any other: the modules are gone, so nothing is left to hear.
        // It was never released here, which kept the capture thread, the mixer and the TTS executor alive after the
        // client asked to close.
        shutdownAudio.run();
        closeDiagnostics.run();
        closeIndex.run();
    }

    private static void unload(SkillFileProvider provider) {
        if (provider != null) {
            provider.unload();
        }
    }

    private static void unload(TextCapabilityProvider provider) {
        if (provider != null) {
            provider.unload();
        }
    }

    private static void refreshCatalogQuietly() {
        if (!ConnectionToolChannel.isAvailable()) {
            return;
        }
        try {
            ConnectionToolChannel.refreshCatalog().exceptionally(failure -> null);
        } catch (RuntimeException ignored) {
            // A failed refresh leaves the previous declarations in place; the next tick tries again.
        }
    }

    public ClientSessionState state() {
        return state;
    }

    public long generation() {
        return generation.get();
    }

    private void completeWorldStart(long requestGeneration, Boolean running, Throwable failure) {
        synchronized (stateLock) {
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
        synchronized (stateLock) {
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