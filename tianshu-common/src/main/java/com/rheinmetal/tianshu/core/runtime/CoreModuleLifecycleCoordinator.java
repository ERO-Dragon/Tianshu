package com.rheinmetal.tianshu.core.runtime;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.ModuleLifecycleException;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class CoreModuleLifecycleCoordinator {
    private final IGameEnvironment env;
    private final ProtocolRuntime protocolRuntime;
    private final TianshuModuleHost moduleHost;
    private final ModuleServiceRegistry moduleServices;
    private final VoiceResourceManager voiceResourceManager;
    private final ModuleRuntimeState runtimeState;
    private final RuntimeReadinessState readinessState;
    private final RuntimeInterruptionService interruptionService;
    private final Runnable moduleBuilder;
    private final CoreLifecycleCommandQueue commands = new CoreLifecycleCommandQueue();
    private final AtomicLong sessionBarrier = new AtomicLong();
    private final AtomicBoolean destroyRequested = new AtomicBoolean();
    private final Object refreshMonitor = new Object();
    private final Object destroyMonitor = new Object();

    private volatile boolean initialized;
    private volatile CoreLifecyclePhase phase = CoreLifecyclePhase.CREATED;
    private CompletableFuture<CoreRuntimeStatus> refreshFuture;
    private CompletableFuture<CoreRuntimeStatus> destroyFuture;

    public CoreModuleLifecycleCoordinator(
            IGameEnvironment env,
            ProtocolRuntime protocolRuntime,
            TianshuModuleHost moduleHost,
            ModuleServiceRegistry moduleServices,
            VoiceResourceManager voiceResourceManager,
            ModuleRuntimeState runtimeState,
            RuntimeReadinessState readinessState,
            RuntimeInterruptionService interruptionService,
            Runnable moduleBuilder
    ) {
        this.env = env;
        this.protocolRuntime = protocolRuntime;
        this.moduleHost = moduleHost;
        this.moduleServices = moduleServices;
        this.voiceResourceManager = voiceResourceManager;
        this.runtimeState = runtimeState;
        this.readinessState = readinessState;
        this.interruptionService = interruptionService;
        this.moduleBuilder = moduleBuilder;
        this.voiceResourceManager.voiceTriggers().addChangeListener(this.voiceResourceManager::materialize);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public CoreLifecyclePhase phase() {
        return phase;
    }

    public CoreRuntimeStatus status() {
        return new CoreRuntimeStatus(
                phase,
                readinessState.getPhase(),
                initialized,
                runtimeState.capabilities().statuses(),
                moduleHost.moduleStatuses()
        );
    }

    public CompletableFuture<CoreRuntimeStatus> startSession() {
        if (destroyRequested.get()) {
            return CompletableFuture.completedFuture(status());
        }
        long barrier = sessionBarrier.get();
        return commands.submit("start_session", () -> {
            if (destroyRequested.get() || barrier != sessionBarrier.get() || initialized) {
                return status();
            }
            runLifecycle(false, CoreLifecyclePhase.INITIALIZING, RuntimeEnginePhase.INITIALIZING);
            return status();
        });
    }

    public CompletableFuture<CoreRuntimeStatus> refresh(RuntimeRefreshReason reason) {
        if (destroyRequested.get()) {
            return CompletableFuture.completedFuture(status());
        }
        synchronized (refreshMonitor) {
            if (refreshFuture != null && !refreshFuture.isDone()) {
                return refreshFuture;
            }
            long barrier = sessionBarrier.get();
            RuntimeRefreshReason refreshReason = reason == null ? RuntimeRefreshReason.MANUAL : reason;
            CompletableFuture<CoreRuntimeStatus> submitted = commands.submit("refresh", () -> {
                if (destroyRequested.get() || barrier != sessionBarrier.get()) {
                    return status();
                }
                if (!initialized) {
                    runLifecycle(false, CoreLifecyclePhase.INITIALIZING, RuntimeEnginePhase.INITIALIZING);
                    return status();
                }
                env.info("开始刷新模块生命周期，reason=" + refreshReason);
                phase = CoreLifecyclePhase.REFRESHING;
                readinessState.setPhase(RuntimeEnginePhase.RESTARTING);
                if (!shutdownModules(true, "refresh")) {
                    initialized = false;
                    phase = CoreLifecyclePhase.FAILED;
                    readinessState.setPhase(RuntimeEnginePhase.IDLE);
                    return status();
                }
                runLifecycle(true, CoreLifecyclePhase.REFRESHING, RuntimeEnginePhase.RESTARTING);
                return status();
            });
            refreshFuture = submitted;
            submitted.whenComplete((ignored, failure) -> {
                synchronized (refreshMonitor) {
                    if (refreshFuture == submitted) {
                        refreshFuture = null;
                    }
                }
            });
            return submitted;
        }
    }

    public CompletableFuture<CoreRuntimeStatus> stopSession() {
        sessionBarrier.incrementAndGet();
        if (destroyRequested.get()) {
            return CompletableFuture.completedFuture(status());
        }
        interruptionService.interruptOngoingProcessing(
                com.rheinmetal.tianshu.protocol.payload.RuntimeInterruptPayload.Reason.CLIENT_SHUTDOWN,
                "client_world_unload"
        );
        return commands.submit("stop_session", () -> {
            if (destroyRequested.get()) {
                return status();
            }
            phase = CoreLifecyclePhase.DESTROYING;
            shutdownModules(true, "session stop");
            initialized = false;
            phase = CoreLifecyclePhase.CREATED;
            readinessState.setPhase(RuntimeEnginePhase.IDLE);
            return status();
        });
    }

    public CompletableFuture<CoreRuntimeStatus> destroy() {
        synchronized (destroyMonitor) {
            if (destroyFuture != null) {
                return destroyFuture;
            }
            destroyRequested.set(true);
            sessionBarrier.incrementAndGet();
            interruptionService.interruptOngoingProcessing(
                    com.rheinmetal.tianshu.protocol.payload.RuntimeInterruptPayload.Reason.CLIENT_SHUTDOWN,
                    "client_shutdown"
            );
            CompletableFuture<CoreRuntimeStatus> submitted = commands.submit("destroy", () -> {
                phase = CoreLifecyclePhase.DESTROYING;
                try {
                    shutdownModules(true, "destroy");
                    protocolRuntime.close();
                } finally {
                    initialized = false;
                    readinessState.setPhase(RuntimeEnginePhase.DESTROYED);
                    phase = CoreLifecyclePhase.DESTROYED;
                }
                return status();
            });
            destroyFuture = submitted;
            submitted.whenComplete((ignored, failure) -> commands.close());
            return submitted;
        }
    }

    private void runLifecycle(
            boolean rebuild,
            CoreLifecyclePhase activePhase,
            RuntimeEnginePhase activeEnginePhase
    ) {
        try {
            env.info("开始初始化模块生命周期...");
            phase = activePhase;
            readinessState.setPhase(activeEnginePhase);

            if (rebuild || !initialized) {
                moduleBuilder.run();
                ModuleRegistrationContext registrationContext = new ModuleRegistrationContext(protocolRuntime, moduleServices);
                moduleHost.registerAll(registrationContext, runtimeState.capabilities());
            }

            voiceResourceManager.materialize();
            ModuleRuntimeContext runtimeContext = new ModuleRuntimeContext(protocolRuntime, moduleServices, voiceResourceManager, runtimeState, env.diagnostics());
            moduleHost.prepareAll(runtimeContext);
            moduleHost.startAll(runtimeContext);

            initialized = true;
            phase = CoreLifecyclePhase.RUNNING;
            readinessState.setPhase(RuntimeEnginePhase.FULLY_READY);
            env.info("模块生命周期初始化完成");
        } catch (ModuleLifecycleException exception) {
            env.error("模块生命周期执行失败：module=" + exception.moduleId() + ", phase=" + exception.phase(), exception);
            handleLifecycleFailure();
        } catch (Exception exception) {
            env.error("模块生命周期初始化失败", exception);
            handleLifecycleFailure();
        }
    }

    private void handleLifecycleFailure() {
        initialized = false;
        phase = CoreLifecyclePhase.FAILED;
        readinessState.setPhase(RuntimeEnginePhase.IDLE);
        shutdownModules(false, "failure cleanup");
    }

    private boolean shutdownModules(boolean clearCapabilities, String operation) {
        try {
            try {
                moduleHost.stopAll();
            } finally {
                try {
                    moduleHost.destroyAll();
                } finally {
                    try {
                        moduleHost.unregisterAll(protocolRuntime);
                    } finally {
                        moduleHost.clearActiveInstallations();
                        if (clearCapabilities) {
                            runtimeState.capabilities().clear();
                        }
                        moduleServices.clear();
                    }
                }
            }
            return true;
        } catch (ModuleLifecycleException exception) {
            env.error(
                    "模块生命周期清理失败：operation=" + operation
                            + ", module=" + exception.moduleId()
                            + ", phase=" + exception.phase(),
                    exception
            );
            return false;
        } catch (Exception exception) {
            env.error("模块生命周期清理失败：operation=" + operation, exception);
            return false;
        }
    }
}
