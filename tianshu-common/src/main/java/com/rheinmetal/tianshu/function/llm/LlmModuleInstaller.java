package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.scope.WorldIdentityProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

public final class LlmModuleInstaller implements TianshuFunctionModuleInstaller {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ModuleRuntimeAccess moduleRuntime;

    public LlmModuleInstaller(IGameEnvironment env, ITianshuConfig config, ModuleRuntimeAccess moduleRuntime) {
        this.env = env;
        this.config = config;
        this.moduleRuntime = moduleRuntime;
    }

    public LlmModuleInstaller(IGameEnvironment env, ITianshuConfig config, ModuleRuntimeAccess moduleRuntime, WorldIdentityProvider ignoredWorldIdentityProvider) {
        this(env, config, moduleRuntime);
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(
                new LlmModule(env, config, moduleRuntime),
                LlmRuntimeCapabilities.LLM_REQUEST,
                LlmRuntimeCapabilities.LLM_CACHE_MANAGE,
                LlmRuntimeCapabilities.LLM_PRIMITIVE_QUERY
        );
    }
}
