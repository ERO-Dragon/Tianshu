package com.rheinmetal.tianshu.core.lifecycle.module;

import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistry;

public class ModuleRegistrationContext {
    private final ModuleProtocolAccess protocol;
    private final ModuleServiceRegistry services;

    public ModuleRegistrationContext(ModuleProtocolAccess protocol, ModuleServiceRegistry services) {
        this.protocol = protocol;
        this.services = services;
    }

    public ModuleProtocolAccess protocol() {
        return protocol;
    }

    public VoiceTriggerRegistry voiceTriggers() {
        return protocol.voiceTriggers();
    }

    public ModuleServiceRegistry services() {
        return services;
    }
}
