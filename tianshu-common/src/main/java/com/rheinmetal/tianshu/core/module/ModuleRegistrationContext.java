package com.rheinmetal.tianshu.core.module;

import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistry;

public class ModuleRegistrationContext {
    private final ProtocolRuntime protocolRuntime;
    private final ModuleServiceRegistry services;

    public ModuleRegistrationContext(ProtocolRuntime protocolRuntime, ModuleServiceRegistry services) {
        this.protocolRuntime = protocolRuntime;
        this.services = services;
    }

    public ProtocolRuntime protocolRuntime() {
        return protocolRuntime;
    }

    public VoiceTriggerRegistry voiceTriggers() {
        return protocolRuntime.voiceTriggers();
    }

    public ModuleServiceRegistry services() {
        return services;
    }
}
