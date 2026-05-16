package com.rheinmetal.tianshu.integration;

import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapabilityStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.integration.CoreCapabilityProbe;
import com.rheinmetal.tianshu.protocol.integration.IntegrationModuleDeclaration;
import com.rheinmetal.tianshu.protocol.summary.StateSummary;
import com.rheinmetal.tianshu.protocol.summary.StateSummaryQuery;
import com.rheinmetal.tianshu.protocol.summary.StateSummaryRegistry;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistrationResult;

import java.util.List;
import java.util.Optional;

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
    public void submitStateSummary(StateSummary summary) {
        coreManager.protocolRuntime().stateSummaries().submit(summary);
    }

    @Override
    public List<StateSummary> queryStateSummaries(StateSummaryQuery query) {
        StateSummaryRegistry registry = coreManager.protocolRuntime().stateSummaries();
        if (query == null) {
            return registry.all();
        }
        if (query.hasModuleFilter() && query.hasTypeFilter()) {
            return registry.latest(query.moduleId(), query.summaryType()).stream().toList();
        }
        if (query.hasModuleFilter()) {
            return registry.byModule(query.moduleId());
        }
        if (query.hasTypeFilter()) {
            return registry.byType(query.summaryType());
        }
        return registry.all();
    }

    @Override
    public StateSummaryRegistry stateSummaries() {
        return coreManager.protocolRuntime().stateSummaries();
    }

    @Override
    public void submit(TianshuEnvelope envelope) {
        coreManager.protocolRuntime().submit(envelope);
    }

    @Override
    public Optional<TianshuEnvelope> request(TianshuEnvelope envelope) {
        return coreManager.protocolRuntime().request(envelope);
    }
}
