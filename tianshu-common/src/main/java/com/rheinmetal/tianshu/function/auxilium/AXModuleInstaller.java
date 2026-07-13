package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class AXModuleInstaller implements TianshuFunctionModuleInstaller {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolRuntime protocolRuntime;
    private final AXWorldIdentityProvider worldIdentityProvider;
    private final AXPromptLanguageProvider promptLanguageProvider;
    private final AXAssistantSettings assistantSettings;
    private final AXRuntimePolicy runtimePolicy;
    private final AXOutputSettings outputSettings;
    private final AXChatOutputSink chatOutputSink;

    public AXModuleInstaller(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime protocolRuntime) {
        this(env, config, protocolRuntime, null);
    }

    public AXModuleInstaller(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime protocolRuntime, AXWorldIdentityProvider worldIdentityProvider) {
        this(env, config, protocolRuntime, worldIdentityProvider, null, AXAssistantSettings.DEFAULT, AXOutputSettings.DEFAULT, AXChatOutputSink.NOOP);
    }

    public AXModuleInstaller(
            IGameEnvironment env,
            ITianshuConfig config,
            ProtocolRuntime protocolRuntime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AXAssistantSettings assistantSettings,
            AXOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink
    ) {
        this(env, config, protocolRuntime, worldIdentityProvider, promptLanguageProvider, assistantSettings, AXRuntimePolicy.defaults(), outputSettings, chatOutputSink);
    }

    public AXModuleInstaller(
            IGameEnvironment env,
            ITianshuConfig config,
            ProtocolRuntime protocolRuntime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AXAssistantSettings assistantSettings,
            AXRuntimePolicy runtimePolicy,
            AXOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink
    ) {
        this.env = env;
        this.config = config;
        this.protocolRuntime = protocolRuntime;
        this.worldIdentityProvider = worldIdentityProvider;
        this.promptLanguageProvider = promptLanguageProvider;
        this.assistantSettings = assistantSettings == null ? AXAssistantSettings.DEFAULT : assistantSettings;
        this.runtimePolicy = runtimePolicy == null ? AXRuntimePolicy.defaults() : runtimePolicy;
        this.outputSettings = outputSettings == null ? AXOutputSettings.DEFAULT : outputSettings;
        this.chatOutputSink = chatOutputSink == null ? AXChatOutputSink.NOOP : chatOutputSink;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        if (!assistantSettings.assistantEnabled()) {
            return;
        }
        moduleHost.registerOptionalModule(new AXModule(env, config, protocolRuntime, worldIdentityProvider, promptLanguageProvider, assistantSettings, runtimePolicy, outputSettings, chatOutputSink));
    }
}
