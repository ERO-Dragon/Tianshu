package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.weaving.settings.LlmConfiguration;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.model.lifecycle.NativeModelLoadQueue;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

public final class LlmModuleInstaller implements TianshuModuleInstaller {
    private final IGameEnvironment env;
    private final LlmConfiguration config;
    private final ModuleRuntimeAccess moduleRuntime;
    /** Serial gate shared with the speech modules; null installs the module without one. */
    private final NativeModelLoadQueue loadQueue;

    public LlmModuleInstaller(IGameEnvironment env, LlmConfiguration config, ModuleRuntimeAccess moduleRuntime) {
        this(env, config, moduleRuntime, null);
    }

    public LlmModuleInstaller(IGameEnvironment env, LlmConfiguration config, ModuleRuntimeAccess moduleRuntime,
                              NativeModelLoadQueue loadQueue) {
        this.env = env;
        this.config = config;
        this.moduleRuntime = moduleRuntime;
        this.loadQueue = loadQueue;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(
                new LlmModule(env, config, moduleRuntime, loadQueue),
                LlmRuntimeCapabilities.LLM_REQUEST,
                LlmRuntimeCapabilities.LLM_CACHE_MANAGE,
                LlmRuntimeCapabilities.LLM_PRIMITIVE_QUERY
        );
    }
}
