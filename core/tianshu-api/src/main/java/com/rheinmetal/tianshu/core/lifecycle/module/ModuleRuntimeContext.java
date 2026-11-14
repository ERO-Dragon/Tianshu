package com.rheinmetal.tianshu.core.lifecycle.module;

import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;

public class ModuleRuntimeContext {
    private final ModuleProtocolAccess protocol;
    private final ModuleServiceRegistry services;
    private final TriggerRegistry triggers;
    private final ModuleRuntimeState runtimeState;
    private final DiagnosticSink diagnostics;

    public ModuleRuntimeContext(ModuleProtocolAccess protocol, ModuleServiceRegistry services, TriggerRegistry triggers, ModuleRuntimeState runtimeState) {
        this(protocol, services, triggers, runtimeState, DiagnosticSink.NOOP);
    }

    public ModuleRuntimeContext(ModuleProtocolAccess protocol, ModuleServiceRegistry services, TriggerRegistry triggers, ModuleRuntimeState runtimeState, DiagnosticSink diagnostics) {
        this.protocol = protocol;
        this.services = services;
        this.triggers = triggers;
        this.runtimeState = runtimeState;
        this.diagnostics = diagnostics == null ? DiagnosticSink.NOOP : diagnostics;
    }

    public ModuleProtocolAccess protocol() {
        return protocol;
    }

    public ModuleServiceRegistry services() {
        return services;
    }

    public TriggerRegistry triggers() {
        return triggers;
    }

    public ModuleRuntimeState runtimeState() {
        return runtimeState;
    }

    public DiagnosticSink diagnostics() {
        return diagnostics;
    }
}
