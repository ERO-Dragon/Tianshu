package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class AssistantSpeechBridgeInstaller implements TianshuFunctionModuleInstaller {
    private final ProtocolRuntime protocolRuntime;

    public AssistantSpeechBridgeInstaller(ProtocolRuntime protocolRuntime) {
        this.protocolRuntime = protocolRuntime;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new AssistantSpeechBridge(protocolRuntime));
    }
}
