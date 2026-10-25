package com.rheinmetal.tianshu.core.lifecycle.module;

import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistry;

public class ModuleRuntimeContext {
    private final ModuleProtocolAccess protocol;
    private final ModuleServiceRegistry services;
    private final VoiceTriggerRegistry voiceTriggers;
    private final ModuleRuntimeState runtimeState;
    private final DiagnosticSink diagnostics;

    public ModuleRuntimeContext(ModuleProtocolAccess protocol, ModuleServiceRegistry services, VoiceTriggerRegistry voiceTriggers, ModuleRuntimeState runtimeState) {
        this(protocol, services, voiceTriggers, runtimeState, DiagnosticSink.NOOP);
    }

    public ModuleRuntimeContext(ModuleProtocolAccess protocol, ModuleServiceRegistry services, VoiceTriggerRegistry voiceTriggers, ModuleRuntimeState runtimeState, DiagnosticSink diagnostics) {
        this.protocol = protocol;
        this.services = services;
        this.voiceTriggers = voiceTriggers;
        this.runtimeState = runtimeState;
        this.diagnostics = diagnostics == null ? DiagnosticSink.NOOP : diagnostics;
    }

    public ModuleProtocolAccess protocol() {
        return protocol;
    }

    public ModuleServiceRegistry services() {
        return services;
    }

    public VoiceTriggerRegistry voiceTriggers() {
        return voiceTriggers;
    }

    public ModuleRuntimeState runtimeState() {
        return runtimeState;
    }

    public DiagnosticSink diagnostics() {
        return diagnostics;
    }
}
