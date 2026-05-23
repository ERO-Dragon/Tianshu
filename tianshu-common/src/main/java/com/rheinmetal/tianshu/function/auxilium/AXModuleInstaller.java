package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.rag.RuntimeFactTextResolver;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.provider.WorldStateProvider;

public final class AXModuleInstaller implements TianshuFunctionModuleInstaller {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolRuntime protocolRuntime;
    private final AXWorldIdentityProvider worldIdentityProvider;
    private final WorldStateProvider worldStateProvider;
    private final RuntimeFactTextResolver runtimeFactTextResolver;
    private final AXPromptLanguageProvider promptLanguageProvider;

    public AXModuleInstaller(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime protocolRuntime) {
        this(env, config, protocolRuntime, null, null, null);
    }

    public AXModuleInstaller(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime protocolRuntime, AXWorldIdentityProvider worldIdentityProvider, WorldStateProvider worldStateProvider) {
        this(env, config, protocolRuntime, worldIdentityProvider, worldStateProvider, null);
    }

    public AXModuleInstaller(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime protocolRuntime, AXWorldIdentityProvider worldIdentityProvider, WorldStateProvider worldStateProvider, RuntimeFactTextResolver runtimeFactTextResolver) {
        this(env, config, protocolRuntime, worldIdentityProvider, worldStateProvider, runtimeFactTextResolver, null);
    }

    public AXModuleInstaller(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime protocolRuntime, AXWorldIdentityProvider worldIdentityProvider, WorldStateProvider worldStateProvider, RuntimeFactTextResolver runtimeFactTextResolver, AXPromptLanguageProvider promptLanguageProvider) {
        this.env = env;
        this.config = config;
        this.protocolRuntime = protocolRuntime;
        this.worldIdentityProvider = worldIdentityProvider;
        this.worldStateProvider = worldStateProvider;
        this.runtimeFactTextResolver = runtimeFactTextResolver;
        this.promptLanguageProvider = promptLanguageProvider;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new AXModule(env, config, protocolRuntime, worldIdentityProvider, worldStateProvider, runtimeFactTextResolver, promptLanguageProvider));
    }
}
