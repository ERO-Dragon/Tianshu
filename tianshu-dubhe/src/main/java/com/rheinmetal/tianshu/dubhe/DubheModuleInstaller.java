package com.rheinmetal.tianshu.dubhe;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegateDeliverySink;
import com.rheinmetal.tianshu.dubhe.dialogue.output.DubheChatOutputSink;
import com.rheinmetal.tianshu.dubhe.dialogue.output.DubheOutputSettings;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventSpeechSink;
import com.rheinmetal.tianshu.dubhe.shared.producer.DubheGameEventTextProvider;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheWorldIdentityProvider;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStorageConfiguration;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

public final class DubheModuleInstaller implements TianshuModuleInstaller {
    private final IGameEnvironment env;
    private final DubheStorageConfiguration storageConfiguration;
    private final ModuleRuntimeAccess moduleRuntime;
    private final DubheWorldIdentityProvider worldIdentityProvider;
    private final DubhePromptLanguageProvider promptLanguageProvider;
    private final DubheAssistantSettings assistantSettings;
    private final DubheRuntimePolicy runtimePolicy;
    private final DubheOutputSettings outputSettings;
    private final DubheChatOutputSink chatOutputSink;
    private final DubheDelegateDeliverySink delegateSpeech;
    private final DubheEventSpeechSink eventSpeech;
    /** Language table the event layer renders through; {@link DubheGameEventTextProvider#NONE} when unwired. */
    private final DubheGameEventTextProvider eventTexts;

    public DubheModuleInstaller(IGameEnvironment env, DubheStorageConfiguration storageConfiguration, ModuleRuntimeAccess moduleRuntime) {
        this(env, storageConfiguration, moduleRuntime, null);
    }

    public DubheModuleInstaller(IGameEnvironment env, DubheStorageConfiguration storageConfiguration, ModuleRuntimeAccess moduleRuntime, DubheWorldIdentityProvider worldIdentityProvider) {
        this(env, storageConfiguration, moduleRuntime, worldIdentityProvider, null, DubheAssistantSettings.DEFAULT, DubheOutputSettings.DEFAULT, DubheChatOutputSink.NOOP);
    }

    public DubheModuleInstaller(
            IGameEnvironment env,
            DubheStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess moduleRuntime,
            DubheWorldIdentityProvider worldIdentityProvider,
            DubhePromptLanguageProvider promptLanguageProvider,
            DubheAssistantSettings assistantSettings,
            DubheOutputSettings outputSettings,
            DubheChatOutputSink chatOutputSink
    ) {
        this(env, storageConfiguration, moduleRuntime, worldIdentityProvider, promptLanguageProvider, assistantSettings, DubheRuntimePolicy.defaults(), outputSettings, chatOutputSink);
    }

    public DubheModuleInstaller(
            IGameEnvironment env,
            DubheStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess moduleRuntime,
            DubheWorldIdentityProvider worldIdentityProvider,
            DubhePromptLanguageProvider promptLanguageProvider,
            DubheAssistantSettings assistantSettings,
            DubheRuntimePolicy runtimePolicy,
            DubheOutputSettings outputSettings,
            DubheChatOutputSink chatOutputSink
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
    public DubheModuleInstaller(
            IGameEnvironment env,
            DubheStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess moduleRuntime,
            DubheWorldIdentityProvider worldIdentityProvider,
            DubhePromptLanguageProvider promptLanguageProvider,
            DubheAssistantSettings assistantSettings,
            DubheOutputSettings outputSettings,
            DubheChatOutputSink chatOutputSink,
            DubheDelegateDeliverySink delegateSpeech,
            DubheEventSpeechSink eventSpeech
    ) {
        this(env, storageConfiguration, moduleRuntime, worldIdentityProvider, promptLanguageProvider, assistantSettings,
                DubheRuntimePolicy.defaults(), outputSettings, chatOutputSink, delegateSpeech, eventSpeech);
    }


    /**
     * @param delegateSpeech voice channel for delegation lines; {@code null} keeps the diagnostic only
     * @param eventSpeech    voice channel for game event sentences; {@code null} keeps the diagnostic only
     */
    public DubheModuleInstaller(
            IGameEnvironment env,
            DubheStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess moduleRuntime,
            DubheWorldIdentityProvider worldIdentityProvider,
            DubhePromptLanguageProvider promptLanguageProvider,
            DubheAssistantSettings assistantSettings,
            DubheRuntimePolicy runtimePolicy,
            DubheOutputSettings outputSettings,
            DubheChatOutputSink chatOutputSink,
            DubheDelegateDeliverySink delegateSpeech,
            DubheEventSpeechSink eventSpeech
    ) {
        this(env, storageConfiguration, moduleRuntime, worldIdentityProvider, promptLanguageProvider,
                assistantSettings, runtimePolicy, outputSettings, chatOutputSink, delegateSpeech, eventSpeech, null);
    }

    /**
     * @param eventTexts language table the event layer renders through; {@code null} renders nothing, which
     *                   makes every event fall back to its kind code
     */
    public DubheModuleInstaller(
            IGameEnvironment env,
            DubheStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess moduleRuntime,
            DubheWorldIdentityProvider worldIdentityProvider,
            DubhePromptLanguageProvider promptLanguageProvider,
            DubheAssistantSettings assistantSettings,
            DubheRuntimePolicy runtimePolicy,
            DubheOutputSettings outputSettings,
            DubheChatOutputSink chatOutputSink,
            DubheDelegateDeliverySink delegateSpeech,
            DubheEventSpeechSink eventSpeech,
            DubheGameEventTextProvider eventTexts
    ) {
        this.env = env;
        this.storageConfiguration = storageConfiguration;
        this.moduleRuntime = moduleRuntime;
        this.worldIdentityProvider = worldIdentityProvider;
        this.promptLanguageProvider = promptLanguageProvider;
        this.assistantSettings = assistantSettings == null ? DubheAssistantSettings.DEFAULT : assistantSettings;
        this.runtimePolicy = runtimePolicy == null ? DubheRuntimePolicy.defaults() : runtimePolicy;
        this.outputSettings = outputSettings == null ? DubheOutputSettings.DEFAULT : outputSettings;
        this.chatOutputSink = chatOutputSink == null ? DubheChatOutputSink.NOOP : chatOutputSink;
        this.delegateSpeech = delegateSpeech;
        this.eventSpeech = eventSpeech;
        this.eventTexts = eventTexts;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        if (!assistantSettings.assistantEnabled()) {
            return;
        }
        moduleHost.registerOptionalModule(new DubheModule(
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
