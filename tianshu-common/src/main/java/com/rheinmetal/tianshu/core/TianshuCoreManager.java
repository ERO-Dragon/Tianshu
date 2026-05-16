package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.EmptyTianshuModuleAssembler;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssembler;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblerFactory;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.runtime.CoreLifecyclePhase;
import com.rheinmetal.tianshu.core.runtime.CoreModuleLifecycleCoordinator;
import com.rheinmetal.tianshu.core.runtime.CoreRuntimeStatus;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapabilityStatus;
import com.rheinmetal.tianshu.core.runtime.RuntimeEnginePhase;
import com.rheinmetal.tianshu.core.runtime.RuntimeInterruptionService;
import com.rheinmetal.tianshu.core.runtime.RuntimeReadinessState;
import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;
import com.rheinmetal.tianshu.event.InterruptEvent;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.protocol.payload.RuntimeInterruptPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;


public class TianshuCoreManager {

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final INativeLibBridge nativeLibBridge;
    private final TianshuEventBus eventBus;
    private final ProtocolRuntime protocolRuntime;
    private final TianshuModuleHost moduleHost;
    private final ModuleServiceRegistry moduleServices;
    private final VoiceResourceManager voiceResourceManager;
    private final ModuleRuntimeState runtimeState;
    private final RuntimeReadinessState state;
    private final EnvSetupManager envSetupManager;
    private final RuntimeInterruptionService interruptionService;
    private final TianshuModuleAssembler moduleAssembler;
    private final CoreModuleLifecycleCoordinator lifecycleCoordinator;
    private final AtomicBoolean restarting = new AtomicBoolean(false);

    public TianshuCoreManager(IGameEnvironment env, ITianshuConfig config, INativeLibBridge nativeLibBridge, IAudioBridge audioBridge) {
        this(env, config, nativeLibBridge, audioBridge, null);
    }

    public TianshuCoreManager(IGameEnvironment env, ITianshuConfig config, INativeLibBridge nativeLibBridge, IAudioBridge audioBridge, TianshuModuleAssemblerFactory moduleAssemblerFactory) {
        this.env = env;
        this.config = config;
        this.nativeLibBridge = nativeLibBridge;
        this.eventBus = new TianshuEventBus(env);
        this.protocolRuntime = ProtocolBootstrap.create(env::executeOnMainThread);
        this.moduleHost = new TianshuModuleHost(env);
        this.moduleServices = new ModuleServiceRegistry();
        this.voiceResourceManager = new VoiceResourceManager(env, config);
        this.runtimeState = new ModuleRuntimeState();
        this.state = runtimeState.readiness();
        this.envSetupManager = new EnvSetupManager(env, nativeLibBridge, protocolRuntime.executors());
        this.interruptionService = new RuntimeInterruptionService(eventBus, protocolRuntime.runtimeInterrupts());
        TianshuModuleAssemblyContext moduleAssemblyContext = new TianshuModuleAssemblyContext(
                env,
                config,
                nativeLibBridge,
                audioBridge,
                eventBus,
                protocolRuntime,
                this::runtimeReadyForRequests,
                this::interruptOngoingProcessing
        );
        this.moduleAssembler = moduleAssemblerFactory == null
                ? new EmptyTianshuModuleAssembler()
                : moduleAssemblerFactory.create(moduleAssemblyContext);
        this.lifecycleCoordinator = new CoreModuleLifecycleCoordinator(
                env,
                envSetupManager,
                protocolRuntime,
                moduleHost,
                moduleServices,
                voiceResourceManager,
                runtimeState,
                state,
                this::assembleManagedModules
        );
    }

    public <T> Optional<T> findService(Class<T> type) {
        return moduleServices.find(type);
    }

    public <T> T requireService(Class<T> type) {
        return moduleServices.require(type);
    }

    public ProtocolRuntime protocolRuntime() {
        return protocolRuntime;
    }

    public ProtocolRuntime getProtocolRuntime() {
        return protocolRuntime();
    }

    public List<TianshuManagedModule> managedModules() {
        return moduleHost.managedModules();
    }

    public boolean isEnvironmentReady() {
        return envSetupManager.isEnvironmentReady();
    }

    public TianshuEventBus eventBus() {
        return eventBus;
    }

    public boolean isEnvironmentSetupCompleted() {
        return envSetupManager.isSetupCompleted();
    }

    public void startEnvironmentSetup(EnvSetupManager.SetupCallback callback) {
        envSetupManager.startSetup(callback);
    }

    public void markEnvironmentSetupCompleted() {
        envSetupManager.markSetupCompleted();
    }

    public boolean isEngineReady() {
        return status().coreRunning();
    }

    public boolean isInitialized() {
        return lifecycleCoordinator.isInitialized();
    }

    public RuntimeCapabilityStatus capabilityStatus(RuntimeCapability capability) {
        return runtimeState.capabilities().status(capability);
    }

    public boolean isCapabilityReady(RuntimeCapability capability) {
        return runtimeState.capabilities().isReady(capability);
    }

    public RuntimeEnginePhase getEnginePhase() {
        return state.getPhase();
    }

    public CoreLifecyclePhase getCoreLifecyclePhase() {
        return lifecycleCoordinator.phase();
    }

    public CoreRuntimeStatus status() {
        return lifecycleCoordinator.status();
    }

    public boolean runtimeReadyForRequests() {
        return status().acceptsRuntimeRequests();
    }

    public void initWorkers() {
        lifecycleCoordinator.initializeIfNeeded();
    }

    private void assembleManagedModules() {
        moduleAssembler.assemble(moduleHost, moduleServices);
    }

    public void restartRuntimeAsync(RuntimeRefreshReason reason, Runnable onComplete) {
        if (!restarting.compareAndSet(false, true)) {
            env.warn("运行时正在重启，忽略重复请求");
            return;
        }
        state.setPhase(RuntimeEnginePhase.RESTARTING);
        RuntimeRefreshReason refreshReason = reason == null ? RuntimeRefreshReason.RESTART_REQUESTED : reason;
        boolean submitted = lifecycleCoordinator.submitRefresh(refreshReason, () -> {
            restarting.set(false);
            state.refreshPhase(lifecycleCoordinator.isInitialized());
            if (onComplete != null) {
                onComplete.run();
            }
        });
        if (!submitted) {
            restarting.set(false);
            state.refreshPhase(lifecycleCoordinator.isInitialized());
        }
    }

    public long interruptOngoingProcessing() {
        env.info("打断正在进行的运行时处理");
        return interruptionService.interruptOngoingProcessing(RuntimeInterruptPayload.Reason.USER_INPUT, "runtime_interrupt");
    }

    public void destroy() {
        env.info("核心管理器：销毁全部资源");

        long stoppedSession = eventBus.beginNewSession();
        eventBus.clearAllQueues();
        eventBus.publishEvent(new InterruptEvent(stoppedSession));

        lifecycleCoordinator.destroyModules();
        protocolRuntime.close();

        eventBus.clearAllQueues();
        state.reset();
        state.setPhase(RuntimeEnginePhase.DESTROYED);
    }

    public void onEnvSetupFinished() {
        env.info("环境配置完成，刷新模块生命周期");
        reloadNatives();
        if (lifecycleCoordinator.isInitialized()) {
            lifecycleCoordinator.submitRefresh(RuntimeRefreshReason.ENVIRONMENT_READY, null);
        } else {
            initWorkers();
        }
    }

    public void refreshRuntimeAsync(RuntimeRefreshReason reason, Runnable onComplete) {
        RuntimeRefreshReason refreshReason = reason == null ? RuntimeRefreshReason.RESOURCE_CHANGED : reason;
        if (lifecycleCoordinator.isInitialized()) {
            lifecycleCoordinator.submitRefresh(refreshReason, onComplete);
        } else {
            initWorkers();
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    public void reloadNatives() {
        try {
            if (!nativeLibBridge.isNativesReady()) {
                nativeLibBridge.extractAndLoadAll();
            }
        } catch (Exception e) {
            env.error("重新加载 Native 库失败", e);
        }
    }
}
