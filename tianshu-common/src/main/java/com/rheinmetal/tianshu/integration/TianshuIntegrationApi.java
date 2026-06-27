package com.rheinmetal.tianshu.integration;

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

public interface TianshuIntegrationApi {
    CoreCapabilityProbe probe();

    boolean isCoreReady();

    boolean isCapabilityReady(RuntimeCapability capability);

    RuntimeCapabilityStatus capabilityStatus(RuntimeCapability capability);

    void registerModule(IntegrationModuleDeclaration declaration);

    void unregisterModule(String moduleId);

    VoiceTriggerRegistrationResult registerVoiceTrigger(VoiceTriggerRegistration registration);

    void unregisterVoiceTriggers(String moduleId);

    void submitModuleStatus(ModuleStatus status);

    List<ModuleStatus> queryModuleStatuses(ModuleStatusQuery query);

    void submit(TianshuEnvelope envelope);
}


