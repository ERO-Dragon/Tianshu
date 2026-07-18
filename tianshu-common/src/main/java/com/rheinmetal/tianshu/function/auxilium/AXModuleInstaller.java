package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageConfiguration;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

public final class AXModuleInstaller implements TianshuModuleInstaller {
    private final IGameEnvironment env;
    private final AXStorageConfiguration storageConfiguration;
    private final ModuleRuntimeAccess moduleRuntime;
    private final AXWorldIdentityProvider worldIdentityProvider;
    private final AXPromptLanguageProvider promptLanguageProvider;
    private final AXAssistantSettings assistantSettings;
    private final AXRuntimePolicy runtimePolicy;
    private final AXOutputSettings outputSettings;
    private final AXChatOutputSink chatOutputSink;

    public AXModuleInstaller(IGameEnvironment env, AXStorageConfiguration storageConfiguration, ModuleRuntimeAccess moduleRuntime) {
        this(env, storageConfiguration, moduleRuntime, null);
    }

    public AXModuleInstaller(IGameEnvironment env, AXStorageConfiguration storageConfiguration, ModuleRuntimeAccess moduleRuntime, AXWorldIdentityProvider worldIdentityProvider) {
        this(env, storageConfiguration, moduleRuntime, worldIdentityProvider, null, AXAssistantSettings.DEFAULT, AXOutputSettings.DEFAULT, AXChatOutputSink.NOOP);
    }

    public AXModuleInstaller(
            IGameEnvironment env,
            AXStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess moduleRuntime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AXAssistantSettings assistantSettings,
            AXOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink
    ) {
        this(env, storageConfiguration, moduleRuntime, worldIdentityProvider, promptLanguageProvider, assistantSettings, AXRuntimePolicy.defaults(), outputSettings, chatOutputSink);
    }

    public AXModuleInstaller(
            IGameEnvironment env,
            AXStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess moduleRuntime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AXAssistantSettings assistantSettings,
            AXRuntimePolicy runtimePolicy,
            AXOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink
    ) {
        this.env = env;
        this.storageConfiguration = storageConfiguration;
        this.moduleRuntime = moduleRuntime;
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
        moduleHost.registerOptionalModule(new AXModule(env, storageConfiguration, moduleRuntime, worldIdentityProvider, promptLanguageProvider, assistantSettings, runtimePolicy, outputSettings, chatOutputSink));
    }
}
