package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateDeliverySink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventSpeechSink;
import com.rheinmetal.tianshu.function.auxilium.producer.AXGameEventTextProvider;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.auxilium.storage.AuxiliumStorageConfiguration;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

public final class AXModuleInstaller implements TianshuModuleInstaller {
    private final IGameEnvironment env;
    private final AuxiliumStorageConfiguration storageConfiguration;
    private final ModuleRuntimeAccess moduleRuntime;
    private final AXWorldIdentityProvider worldIdentityProvider;
    private final AXPromptLanguageProvider promptLanguageProvider;
    private final AuxiliumAssistantSettings assistantSettings;
    private final AXRuntimePolicy runtimePolicy;
    private final AuxiliumOutputSettings outputSettings;
    private final AXChatOutputSink chatOutputSink;
    private final AXDelegateDeliverySink delegateSpeech;
    private final AXEventSpeechSink eventSpeech;
    /** Language table the event layer renders through; {@link AXGameEventTextProvider#NONE} when unwired. */
    private final AXGameEventTextProvider eventTexts;

    public AXModuleInstaller(IGameEnvironment env, AuxiliumStorageConfiguration storageConfiguration, ModuleRuntimeAccess moduleRuntime) {
        this(env, storageConfiguration, moduleRuntime, null);
    }

    public AXModuleInstaller(IGameEnvironment env, AuxiliumStorageConfiguration storageConfiguration, ModuleRuntimeAccess moduleRuntime, AXWorldIdentityProvider worldIdentityProvider) {
        this(env, storageConfiguration, moduleRuntime, worldIdentityProvider, null, AuxiliumAssistantSettings.DEFAULT, AuxiliumOutputSettings.DEFAULT, AXChatOutputSink.NOOP);
    }

    public AXModuleInstaller(
            IGameEnvironment env,
            AuxiliumStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess moduleRuntime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AuxiliumAssistantSettings assistantSettings,
            AuxiliumOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink
    ) {
        this(env, storageConfiguration, moduleRuntime, worldIdentityProvider, promptLanguageProvider, assistantSettings, AXRuntimePolicy.defaults(), outputSettings, chatOutputSink);
    }

    public AXModuleInstaller(
            IGameEnvironment env,
            AuxiliumStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess moduleRuntime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AuxiliumAssistantSettings assistantSettings,
            AXRuntimePolicy runtimePolicy,
            AuxiliumOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink
    ) {
        this(env, storageConfiguration, moduleRuntime, worldIdentityProvider, promptLanguageProvider, assistantSettings,
                runtimePolicy, outputSettings, chatOutputSink, null, null);
    }
    /**
     * Voice-enabled overload that keeps the default run policy.
     *
     * @param delegateSpeech voice channel for delegation lines; {@code null} keeps the diagnostic only
     * @param eventSpeech    voice channel for game event sentences; {@code null} keeps the diagnostic only
     */
    public AXModuleInstaller(
            IGameEnvironment env,
            AuxiliumStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess moduleRuntime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AuxiliumAssistantSettings assistantSettings,
            AuxiliumOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink,
            AXDelegateDeliverySink delegateSpeech,
            AXEventSpeechSink eventSpeech
    ) {
        this(env, storageConfiguration, moduleRuntime, worldIdentityProvider, promptLanguageProvider, assistantSettings,
                AXRuntimePolicy.defaults(), outputSettings, chatOutputSink, delegateSpeech, eventSpeech);
    }


    /**
     * @param delegateSpeech voice channel for delegation lines; {@code null} keeps the diagnostic only
     * @param eventSpeech    voice channel for game event sentences; {@code null} keeps the diagnostic only
     */
    public AXModuleInstaller(
            IGameEnvironment env,
            AuxiliumStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess moduleRuntime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AuxiliumAssistantSettings assistantSettings,
            AXRuntimePolicy runtimePolicy,
            AuxiliumOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink,
            AXDelegateDeliverySink delegateSpeech,
            AXEventSpeechSink eventSpeech
    ) {
        this(env, storageConfiguration, moduleRuntime, worldIdentityProvider, promptLanguageProvider,
                assistantSettings, runtimePolicy, outputSettings, chatOutputSink, delegateSpeech, eventSpeech, null);
    }

    /**
     * @param eventTexts language table the event layer renders through; {@code null} renders nothing, which
     *                   makes every event fall back to its kind code
     */
    public AXModuleInstaller(
            IGameEnvironment env,
            AuxiliumStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess moduleRuntime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AuxiliumAssistantSettings assistantSettings,
            AXRuntimePolicy runtimePolicy,
            AuxiliumOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink,
            AXDelegateDeliverySink delegateSpeech,
            AXEventSpeechSink eventSpeech,
            AXGameEventTextProvider eventTexts
    ) {
        this.env = env;
        this.storageConfiguration = storageConfiguration;
        this.moduleRuntime = moduleRuntime;
        this.worldIdentityProvider = worldIdentityProvider;
        this.promptLanguageProvider = promptLanguageProvider;
        this.assistantSettings = assistantSettings == null ? AuxiliumAssistantSettings.DEFAULT : assistantSettings;
        this.runtimePolicy = runtimePolicy == null ? AXRuntimePolicy.defaults() : runtimePolicy;
        this.outputSettings = outputSettings == null ? AuxiliumOutputSettings.DEFAULT : outputSettings;
        this.chatOutputSink = chatOutputSink == null ? AXChatOutputSink.NOOP : chatOutputSink;
        this.delegateSpeech = delegateSpeech;
        this.eventSpeech = eventSpeech;
        this.eventTexts = eventTexts;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        if (!assistantSettings.assistantEnabled()) {
            return;
        }
        moduleHost.registerOptionalModule(new AXModule(
                env,
                storageConfiguration,
                moduleRuntime,
                worldIdentityProvider,
                promptLanguageProvider,
                assistantSettings,
                runtimePolicy,
                outputSettings,
                chatOutputSink,
                delegateSpeech,
                eventSpeech,
                eventTexts));
    }
}
