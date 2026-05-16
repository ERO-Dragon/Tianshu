package com.rheinmetal.tianshu.function.assistant;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantWorldIdentityProvider;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.provider.WorldStateProvider;

public final class AssistantModuleInstaller implements TianshuFunctionModuleInstaller {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolRuntime protocolRuntime;
    private final AssistantWorldIdentityProvider worldIdentityProvider;
    private final WorldStateProvider worldStateProvider;

    public AssistantModuleInstaller(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime protocolRuntime) {
        this(env, config, protocolRuntime, null, null);
    }

    public AssistantModuleInstaller(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime protocolRuntime, AssistantWorldIdentityProvider worldIdentityProvider, WorldStateProvider worldStateProvider) {
        this.env = env;
        this.config = config;
        this.protocolRuntime = protocolRuntime;
        this.worldIdentityProvider = worldIdentityProvider;
        this.worldStateProvider = worldStateProvider;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new AssistantModule(env, config, protocolRuntime, worldIdentityProvider, worldStateProvider));
    }
}
