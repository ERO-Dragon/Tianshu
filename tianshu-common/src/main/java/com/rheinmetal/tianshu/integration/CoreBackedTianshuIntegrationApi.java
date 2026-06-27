package com.rheinmetal.tianshu.integration;

import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapabilityStatus;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.integration.CoreCapabilityProbe;
import com.rheinmetal.tianshu.protocol.integration.IntegrationModuleDeclaration;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.runtime.ModuleStatusCache;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusQuery;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistrationResult;

import java.util.List;

public final class CoreBackedTianshuIntegrationApi implements TianshuIntegrationApi {
    private final TianshuCoreManager coreManager;

    public CoreBackedTianshuIntegrationApi(TianshuCoreManager coreManager) {
        if (coreManager == null) {
            throw new IllegalArgumentException("coreManager cannot be null");
        }
        this.coreManager = coreManager;
    }

    @Override
    public CoreCapabilityProbe probe() {
        return CoreCapabilityProbe.current();
    }

    @Override
    public boolean isCoreReady() {
        return coreManager.isEngineReady();
    }

    @Override
    public boolean isCapabilityReady(RuntimeCapability capability) {
        return capability != null && coreManager.isCapabilityReady(capability);
    }

    @Override
    public RuntimeCapabilityStatus capabilityStatus(RuntimeCapability capability) {
        return coreManager.capabilityStatus(capability);
    }

    @Override
    public void registerModule(IntegrationModuleDeclaration declaration) {
        coreManager.protocolRuntime().integrationModules().register(declaration);
    }

    @Override
    public void unregisterModule(String moduleId) {
        coreManager.protocolRuntime().integrationModules().unregister(moduleId);
    }

    @Override
    public VoiceTriggerRegistrationResult registerVoiceTrigger(VoiceTriggerRegistration registration) {
        return coreManager.protocolRuntime().voiceTriggers().register(registration);
    }

    @Override
    public void unregisterVoiceTriggers(String moduleId) {
        coreManager.protocolRuntime().voiceTriggers().unregisterModule(moduleId);
    }

    @Override
    public void submitModuleStatus(ModuleStatus status) {
        if (status == null) {
            return;
        }
        coreManager.protocolRuntime().submit(EnvelopeBuilder.eventTopic(
                status.moduleId(),
                ProtocolTopics.MODULE_STATUS,
                PayloadType.MODULE_STATUS,
                new ModuleStatusPayload(status)
        ).build());
    }

    @Override
    public List<ModuleStatus> queryModuleStatuses(ModuleStatusQuery query) {
        ModuleStatusCache cache = coreManager.protocolRuntime().moduleStatusCache();
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

    @Override
    public void submit(TianshuEnvelope envelope) {
        coreManager.protocolRuntime().submit(envelope);
    }
}


