package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class LlmModuleInstaller implements TianshuFunctionModuleInstaller {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final INativeLibBridge nativeLibBridge;
    private final ProtocolRuntime protocolRuntime;

    public LlmModuleInstaller(IGameEnvironment env, ITianshuConfig config, INativeLibBridge nativeLibBridge, ProtocolRuntime protocolRuntime) {
        this.env = env;
        this.config = config;
        this.nativeLibBridge = nativeLibBridge;
        this.protocolRuntime = protocolRuntime;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(
                new LlmModule(env, config, nativeLibBridge, protocolRuntime),
                LlmRuntimeCapabilities.INFERENCE,
                LlmRuntimeCapabilities.TASK
        );
    }
}
