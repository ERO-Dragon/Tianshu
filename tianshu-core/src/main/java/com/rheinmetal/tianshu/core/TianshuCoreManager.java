package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.api.assembly.ModuleRuntimeServices;
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
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.integration.IntegrationModuleDeclaration;
import com.rheinmetal.tianshu.protocol.payload.RuntimeInterruptPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import com.rheinmetal.tianshu.protocol.runtime.ModuleStatusCache;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusQuery;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;

import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistration;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistrationResult;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


public class TianshuCoreManager implements ModuleRuntimeServices {

    private final IGameEnvironment env;
    private final ProtocolRuntime protocolRuntime;
    private final TianshuModuleHost moduleHost;
    private final ModuleServiceRegistry moduleServices;
    private final TriggerRegistry triggers;
    private final ModuleRuntimeState runtimeState;
    private final RuntimeReadinessState state;
    private final RuntimeInterruptionService interruptionService;
    private final TianshuModuleAssembler moduleAssembler;
    private final CoreModuleLifecycleCoordinator lifecycleCoordinator;

    public TianshuCoreManager(IGameEnvironment env, IAudioBridge audioBridge) {
        this(env, audioBridge, null, null);
    }

    public TianshuCoreManager(IGameEnvironment env, IAudioBridge audioBridge, TianshuModuleAssemblerFactory moduleAssemblerFactory) {
        this(env, audioBridge, null, moduleAssemblerFactory);
    }

    /**
     * @param clientPorts the client services a module may use while it builds itself; {@code null} in a
     *                    headless assembly, where modules that need a UI are simply not installed
     */
    public TianshuCoreManager(IGameEnvironment env, IAudioBridge audioBridge, ClientPorts clientPorts,
                              TianshuModuleAssemblerFactory moduleAssemblerFactory) {
        this.env = env;
        this.moduleHost = new TianshuModuleHost(env);
        this.moduleServices = new ModuleServiceRegistry();
        this.triggers = new TriggerRegistry();
        this.protocolRuntime = ProtocolBootstrap.create(env::executeOnMainThread, triggers);
        this.runtimeState = new ModuleRuntimeState();
        this.state = runtimeState.readiness();
        this.interruptionService = new RuntimeInterruptionService(protocolRuntime.runtimeInterrupts());
        TianshuModuleAssemblyContext moduleAssemblyContext = new TianshuModuleAssemblyContext(
                env,
                audioBridge,
                protocolRuntime,
                this::runtimeReadyForRequests,
                this::interruptOngoingProcessing,
                clientPorts,
                this
        );
        this.moduleAssembler = moduleAssemblerFactory == null
                ? new EmptyTianshuModuleAssembler()
                : moduleAssemblerFactory.create(moduleAssemblyContext);
        this.lifecycleCoordinator = new CoreModuleLifecycleCoordinator(
                env,
                protocolRuntime,
                moduleHost,
                moduleServices,
                triggers,
                runtimeState,
                state,
                interruptionService,
                this::assembleManagedModules
        );
    }

    public <T> Optional<T> findService(Class<T> type) {
        return moduleServices.find(type);
    }

    public <T> T requireService(Class<T> type) {
        return moduleServices.require(type);
    }

    public List<TianshuManagedModule> managedModules() {
        return moduleHost.managedModules();
    }

    public boolean isEnvironmentReady() {
        return status().acceptsRuntimeRequests();
    }

    public boolean isEnvironmentSetupCompleted() {
        return lifecycleCoordinator.isInitialized();
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

    public void registerIntegrationModule(IntegrationModuleDeclaration declaration) {
        protocolRuntime.integrationModules().register(declaration);
    }

    public void unregisterIntegrationModule(String moduleId) {
        protocolRuntime.integrationModules().unregister(moduleId);
    }

    public TriggerRegistrationResult registerTrigger(TriggerRegistration registration) {
        return protocolRuntime.triggers().register(registration);
    }

    public void unregisterTriggers(String moduleId) {
        protocolRuntime.triggers().unregisterModule(moduleId);
    }

    public void submitModuleStatus(ModuleStatus status) {
        if (status == null) {
            return;
        }
        submit(EnvelopeBuilder.eventTopic(
                status.moduleId(),
                ProtocolTopics.MODULE_STATUS,
                PayloadType.MODULE_STATUS,
                new ModuleStatusPayload(status)
        ).build());
    }

    public List<ModuleStatus> queryModuleStatuses(ModuleStatusQuery query) {
        ModuleStatusCache cache = protocolRuntime.moduleStatusCache();
        if (query == null) {
            return cache.all();
        }
        if (query.hasModuleFilter() && query.hasTypeFilter()) {
            return cache.latest(query.moduleId(), query.statusType()).stream().toList();
        }
        if (query.hasModuleFilter()) {
            return cache.byModule(query.moduleId());
        }
        if (query.hasTypeFilter()) {
            return cache.byType(query.statusType());
        }
        return cache.all();
    }

    public Optional<ModuleStatus> latestModuleStatus(String moduleId) {
        String normalizedModuleId = moduleId == null ? "" : moduleId.trim();
        if (normalizedModuleId.isEmpty()) {
            return Optional.empty();
        }
        Optional<ModuleStatus> lifecycle = protocolRuntime.moduleStatusCache().latestLifecycle(normalizedModuleId);
        if (lifecycle.isPresent()) {
            return lifecycle;
        }
        return queryModuleStatuses(new ModuleStatusQuery(normalizedModuleId, ""))
                .stream()
                .max(java.util.Comparator.comparingLong(ModuleStatus::updatedAtMillis));
    }

    public Optional<ModuleStatus> latestModuleOperationStatus(String moduleId) {
        String normalizedModuleId = moduleId == null ? "" : moduleId.trim();
        if (normalizedModuleId.isEmpty()) {
            return Optional.empty();
        }
        return queryModuleStatuses(new ModuleStatusQuery(normalizedModuleId, ""))
                .stream()
                .filter(status -> !ModuleStatuses.TYPE_LIFECYCLE.equals(status.statusType()))
                .max(java.util.Comparator.comparingLong(ModuleStatus::updatedAtMillis));
    }

    public void submit(TianshuEnvelope envelope) {
        protocolRuntime.submit(envelope);
    }

    /**
     * Publishes one raw world event on the game's behalf.
     *
     * <p>The adapter observes the game and calls this; the raw shape and its vocabulary are api types, so no feature
     * is named here and an absent consumer changes nothing.</p>
     */
    public void publishWorldEvent(WorldEventPayload event) {
        protocolRuntime.publishWorldEvent(event);
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

    public CompletableFuture<CoreRuntimeStatus> startRuntimeSession() {
        return lifecycleCoordinator.startSession();
    }

    private void assembleManagedModules() {
        moduleAssembler.assemble(moduleHost, moduleServices);
    }

    public CompletableFuture<CoreRuntimeStatus> refreshRuntime(RuntimeRefreshReason reason) {
        RuntimeRefreshReason refreshReason = reason == null ? RuntimeRefreshReason.RESOURCE_CHANGED : reason;
        return lifecycleCoordinator.refresh(refreshReason);

    }

    @Override
    public void requestRefresh(RuntimeRefreshReason reason) {
        refreshRuntime(reason);
    }
    public long interruptOngoingProcessing() {
        env.info("打断正在进行的运行时处理");
        return interruptionService.interruptOngoingProcessing(RuntimeInterruptPayload.Reason.USER_INPUT, "runtime_interrupt");
    }

    public CompletableFuture<CoreRuntimeStatus> destroy() {
        env.info("核心管理器：销毁全部资源");
        return lifecycleCoordinator.destroy();
    }

    public CompletableFuture<CoreRuntimeStatus> stopRuntimeSession() {
        env.info("Core manager: stopping runtime session");
        return lifecycleCoordinator.stopSession();
    }
}
