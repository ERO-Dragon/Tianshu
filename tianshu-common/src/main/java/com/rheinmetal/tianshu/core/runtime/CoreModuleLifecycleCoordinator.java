package com.rheinmetal.tianshu.core.runtime;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.ModuleLifecycleException;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceManager;

public final class CoreModuleLifecycleCoordinator {
    private final IGameEnvironment env;
    private final ProtocolRuntime protocolRuntime;
    private final TianshuModuleHost moduleHost;
    private final ModuleServiceRegistry moduleServices;
    private final VoiceResourceManager voiceResourceManager;
    private final ModuleRuntimeState runtimeState;
    private final RuntimeReadinessState readinessState;
    private final Runnable moduleBuilder;
    private final Object lifecycleLock = new Object();

    private volatile boolean initialized;
    private volatile CoreLifecyclePhase phase = CoreLifecyclePhase.CREATED;
    private volatile ProtocolTaskHandle refreshTask;

    public CoreModuleLifecycleCoordinator(
            IGameEnvironment env,
            ProtocolRuntime protocolRuntime,
            TianshuModuleHost moduleHost,
            ModuleServiceRegistry moduleServices,
            VoiceResourceManager voiceResourceManager,
            ModuleRuntimeState runtimeState,
            RuntimeReadinessState readinessState,
            Runnable moduleBuilder
    ) {
        this.env = env;
        this.protocolRuntime = protocolRuntime;
        this.moduleHost = moduleHost;
        this.moduleServices = moduleServices;
        this.voiceResourceManager = voiceResourceManager;
        this.runtimeState = runtimeState;
        this.readinessState = readinessState;
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

    public void initializeIfNeeded() {
        synchronized (lifecycleLock) {
            if (phase == CoreLifecyclePhase.DESTROYED || phase == CoreLifecyclePhase.DESTROYING || initialized) {
                return;
            }
            runLifecycleLocked(false, CoreLifecyclePhase.INITIALIZING);
        }
    }

    public void refresh(RuntimeRefreshReason reason) {
        synchronized (lifecycleLock) {
            if (phase == CoreLifecyclePhase.DESTROYED || phase == CoreLifecyclePhase.DESTROYING) {
                env.warn("核心生命周期已销毁，忽略模块刷新请求");
                return;
            }

            RuntimeRefreshReason refreshReason = reason == null ? RuntimeRefreshReason.MANUAL : reason;
            env.info("开始刷新模块生命周期，reason=" + refreshReason);
            phase = CoreLifecyclePhase.REFRESHING;
            shutdownModulesLocked();
            runLifecycleLocked(true, CoreLifecyclePhase.REFRESHING);
        }
    }

    public boolean submitRefresh(RuntimeRefreshReason reason, Runnable onComplete) {
        synchronized (lifecycleLock) {
            if (phase == CoreLifecyclePhase.DESTROYED || phase == CoreLifecyclePhase.DESTROYING) {
                env.warn("核心生命周期已销毁，忽略模块刷新请求");
                return false;
            }
            ProtocolTaskHandle currentTask = refreshTask;
            if (currentTask != null && !currentTask.isDone()) {
                env.warn("模块生命周期刷新已在队列中，忽略重复请求");
                return false;
            }
            refreshTask = protocolRuntime.executors().submit(
                    ProtocolTaskSpec.builder()
                            .moduleId("core.lifecycle")
                            .lane(ExecutionLane.MODEL_LOAD)
                            .concurrencyKey("core.lifecycle:refresh")
                            .maxConcurrency(1)
                            .queueCapacity(1)
                            .build(),
                    () -> {
                        try {
                            refresh(reason);
                        } finally {
                            if (onComplete != null) {
                                onComplete.run();
                            }
                        }
                    }
            );
            if (refreshTask.state() == ProtocolTaskState.REJECTED) {
                refreshTask = null;
                env.warn("模块生命周期刷新任务提交被拒绝");
                return false;
            }
            return true;
        }
    }

    public void destroyModules() {
        ProtocolTaskHandle currentTask;
        synchronized (lifecycleLock) {
            phase = CoreLifecyclePhase.DESTROYING;
            currentTask = refreshTask;
            if (currentTask != null && !currentTask.isDone()) {
                currentTask.cancel("core_destroy");
            }
            shutdownModulesLocked();
            initialized = false;
            phase = CoreLifecyclePhase.DESTROYED;
        }
    }

    public void stopSession() {
        ProtocolTaskHandle currentTask;
        synchronized (lifecycleLock) {
            if (phase == CoreLifecyclePhase.DESTROYED || phase == CoreLifecyclePhase.DESTROYING) {
                return;
            }
            phase = CoreLifecyclePhase.DESTROYING;
            currentTask = refreshTask;
            if (currentTask != null && !currentTask.isDone()) {
                currentTask.cancel("core_session_stop");
            }
            shutdownModulesLocked();
            initialized = false;
            phase = CoreLifecyclePhase.CREATED;
        }
    }

    private void runLifecycleLocked(boolean rebuild, CoreLifecyclePhase activePhase) {
        try {
            env.info("开始初始化模块生命周期...");
            phase = activePhase;
            readinessState.setPhase(RuntimeEnginePhase.INITIALIZING);

            if (rebuild || !initialized) {
                moduleBuilder.run();
                ModuleRegistrationContext registrationContext = new ModuleRegistrationContext(protocolRuntime, moduleServices);
                moduleHost.registerAll(registrationContext, runtimeState.capabilities());
            }

            voiceResourceManager.materialize();
            ModuleRuntimeContext runtimeContext = new ModuleRuntimeContext(protocolRuntime, moduleServices, voiceResourceManager, runtimeState);
            moduleHost.prepareAll(runtimeContext);
            moduleHost.startAll(runtimeContext);

            initialized = true;
            phase = CoreLifecyclePhase.RUNNING;
            readinessState.refreshPhase(true);
            env.info("模块生命周期初始化完成");
        } catch (ModuleLifecycleException exception) {
            env.error("模块生命周期执行失败：module=" + exception.moduleId() + ", phase=" + exception.phase(), exception);
            handleLifecycleFailureLocked();
        } catch (Exception exception) {
            env.error("模块生命周期初始化失败", exception);
            handleLifecycleFailureLocked();
        }
    }

    private void handleLifecycleFailureLocked() {
        initialized = false;
        phase = CoreLifecyclePhase.FAILED;
        readinessState.setPhase(RuntimeEnginePhase.IDLE);
        try {
            shutdownModulesLocked(false);
        } catch (ModuleLifecycleException exception) {
            env.error("模块生命周期失败后的清理失败：module=" + exception.moduleId() + ", phase=" + exception.phase(), exception);
        }
    }

    private void shutdownModulesLocked() {
        shutdownModulesLocked(true);
    }

    private void shutdownModulesLocked(boolean clearCapabilities) {
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
    }
}
