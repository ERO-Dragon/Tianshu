package com.rheinmetal.tianshu.core.lifecycle.module;

import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;
import com.rheinmetal.tianshu.protocol.trigger.TriggerAccess;

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

    public TriggerAccess triggers() {
        return protocol.triggers();
    }

    public ModuleServiceRegistry services() {
        return services;
    }
}
