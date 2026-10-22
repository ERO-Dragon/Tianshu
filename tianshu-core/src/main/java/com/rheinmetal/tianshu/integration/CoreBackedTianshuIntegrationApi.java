package com.rheinmetal.tianshu.integration;

import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapabilityStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.integration.CoreCapabilityProbe;
import com.rheinmetal.tianshu.protocol.integration.IntegrationModuleDeclaration;
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
        coreManager.registerIntegrationModule(declaration);
    }

    @Override
    public void unregisterModule(String moduleId) {
        coreManager.unregisterIntegrationModule(moduleId);
    }

    @Override
    public VoiceTriggerRegistrationResult registerVoiceTrigger(VoiceTriggerRegistration registration) {
        return coreManager.registerVoiceTrigger(registration);
    }

    @Override
    public void unregisterVoiceTriggers(String moduleId) {
        coreManager.unregisterVoiceTriggers(moduleId);
    }

    @Override
    public void submitModuleStatus(ModuleStatus status) {
        coreManager.submitModuleStatus(status);
    }

    @Override
    public List<ModuleStatus> queryModuleStatuses(ModuleStatusQuery query) {
        return coreManager.queryModuleStatuses(query);
    }

    @Override
    public void submit(TianshuEnvelope envelope) {
        coreManager.submit(envelope);
    }
}


