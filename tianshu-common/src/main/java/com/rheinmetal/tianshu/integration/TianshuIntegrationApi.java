package com.rheinmetal.tianshu.integration;

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

public interface TianshuIntegrationApi {
    CoreCapabilityProbe probe();

    boolean isCoreReady();

    boolean isCapabilityReady(RuntimeCapability capability);

    RuntimeCapabilityStatus capabilityStatus(RuntimeCapability capability);

    void registerModule(IntegrationModuleDeclaration declaration);

    void unregisterModule(String moduleId);

    VoiceTriggerRegistrationResult registerVoiceTrigger(VoiceTriggerRegistration registration);

    void unregisterVoiceTriggers(String moduleId);

    void submitStateSummary(StateSummary summary);

    List<StateSummary> queryStateSummaries(StateSummaryQuery query);

    StateSummaryRegistry stateSummaries();

    void submit(TianshuEnvelope envelope);

    Optional<TianshuEnvelope> request(TianshuEnvelope envelope);
}
