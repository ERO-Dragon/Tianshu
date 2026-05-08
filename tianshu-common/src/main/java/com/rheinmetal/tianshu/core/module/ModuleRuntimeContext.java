package com.rheinmetal.tianshu.core.module;

import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceSnapshot;

public class ModuleRuntimeContext {
    private final ProtocolRuntime protocolRuntime;
    private final ModuleServiceRegistry services;
    private final VoiceResourceSnapshot voiceResources;
    private final ModuleRuntimeState runtimeState;

    public ModuleRuntimeContext(ProtocolRuntime protocolRuntime, ModuleServiceRegistry services, VoiceResourceSnapshot voiceResources, ModuleRuntimeState runtimeState) {
        this.protocolRuntime = protocolRuntime;
        this.services = services;
        this.voiceResources = voiceResources;
        this.runtimeState = runtimeState;
    }

    public ProtocolRuntime protocolRuntime() {
        return protocolRuntime;
    }

    public ModuleServiceRegistry services() {
        return services;
    }

    public VoiceResourceSnapshot voiceResources() {
        return voiceResources;
    }

    public ModuleRuntimeState runtimeState() {
        return runtimeState;
    }
}
