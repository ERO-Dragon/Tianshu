package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.scope.WorldIdentityProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class LlmModuleInstaller implements TianshuFunctionModuleInstaller {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolRuntime protocolRuntime;
    private final WorldIdentityProvider worldIdentityProvider;

    public LlmModuleInstaller(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime protocolRuntime) {
        this(env, config, protocolRuntime, null);
    }

    public LlmModuleInstaller(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime protocolRuntime, WorldIdentityProvider worldIdentityProvider) {
        this.env = env;
        this.config = config;
        this.protocolRuntime = protocolRuntime;
        this.worldIdentityProvider = worldIdentityProvider;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(
                new LlmModule(env, config, protocolRuntime, worldIdentityProvider),
                LlmRuntimeCapabilities.LLM_REQUEST
        );
    }
}
