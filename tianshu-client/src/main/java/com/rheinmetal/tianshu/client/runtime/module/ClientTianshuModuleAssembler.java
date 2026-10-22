package com.rheinmetal.tianshu.client.runtime.module;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.client.ir.ClientIrNamedObjectEnhancer;
import com.rheinmetal.tianshu.client.ir.ClientNamedObjectIndexManager;
import com.rheinmetal.tianshu.client.event.AXGameEventTextProvider;
import com.rheinmetal.tianshu.client.speech.AXProtocolSpeechOutlet;
import com.rheinmetal.tianshu.client.speech.AXSpeechBridge;
import com.rheinmetal.tianshu.core.lifecycle.CompositeTianshuModuleAssembler;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssembler;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.function.asr.AsrModuleInstaller;
import com.rheinmetal.tianshu.function.auxilium.AXAssistantSettings;
import com.rheinmetal.tianshu.function.auxilium.AXModuleInstaller;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.ia.IaModuleInstaller;
import com.rheinmetal.tianshu.function.llm.LlmModuleInstaller;
import com.rheinmetal.tianshu.function.tts.TtsModuleInstaller;
import com.rheinmetal.tianshu.function.tts.VoiceNotificationService;
import com.rheinmetal.tianshu.model.lifecycle.NativeModelLoadQueue;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class ClientTianshuModuleAssembler implements TianshuModuleAssembler {
    private final CompositeTianshuModuleAssembler delegate;

    public ClientTianshuModuleAssembler(
            IGameEnvironment env,
            ClientFunctionConfigurations configurations,
            IAudioBridge audioBridge,
            ModuleRuntimeAccess moduleRuntime,
            ClientNamedObjectIndexManager indexManager,
            AXPromptLanguageProvider promptLanguageProvider,
            BooleanSupplier voiceInputGate,
            LongSupplier interruptionSignal,
            AXWorldIdentityProvider axWorldIdentityProvider
    ) {
        this(
                env,
                configurations,
                audioBridge,
                moduleRuntime,
                indexManager,
                promptLanguageProvider,
                voiceInputGate,
                interruptionSignal,
                axWorldIdentityProvider,
                AXOutputSettings.DEFAULT,
                AXChatOutputSink.NOOP,
                List.of()
        );
    }

    public ClientTianshuModuleAssembler(
            IGameEnvironment env,
            ClientFunctionConfigurations configurations,
            IAudioBridge audioBridge,
            ModuleRuntimeAccess moduleRuntime,
            ClientNamedObjectIndexManager indexManager,
            AXPromptLanguageProvider promptLanguageProvider,
            BooleanSupplier voiceInputGate,
            LongSupplier interruptionSignal,
            AXWorldIdentityProvider axWorldIdentityProvider,
            AXOutputSettings axOutputSettings,
            AXChatOutputSink axChatOutputSink
    ) {
        this(
                env,
                configurations,
                audioBridge,
                moduleRuntime,
                indexManager,
                promptLanguageProvider,
                voiceInputGate,
                interruptionSignal,
                axWorldIdentityProvider,
                axOutputSettings,
                axChatOutputSink,
                List.of()
        );
    }

    public ClientTianshuModuleAssembler(
            IGameEnvironment env,
            ClientFunctionConfigurations configurations,
            IAudioBridge audioBridge,
            ModuleRuntimeAccess moduleRuntime,
            ClientNamedObjectIndexManager indexManager,
            AXPromptLanguageProvider promptLanguageProvider,
            BooleanSupplier voiceInputGate,
            LongSupplier interruptionSignal,
            AXWorldIdentityProvider axWorldIdentityProvider,
            AXOutputSettings axOutputSettings,
            AXChatOutputSink axChatOutputSink,
            List<TianshuModuleInstaller> neoForgeInstallers
    ) {
        this(
                env,
                configurations,
                audioBridge,
                moduleRuntime,
                indexManager,
                promptLanguageProvider,
                voiceInputGate,
                interruptionSignal,
                axWorldIdentityProvider,
                axOutputSettings,
                axChatOutputSink,
                neoForgeInstallers,
                AXGameEventTextProvider.NONE
        );
    }

    /**
     * @param speechTexts language table door used to resolve out-of-band speech keys on the platform side
     */
    public ClientTianshuModuleAssembler(
            IGameEnvironment env,
            ClientFunctionConfigurations configurations,
            IAudioBridge audioBridge,
            ModuleRuntimeAccess moduleRuntime,
            ClientNamedObjectIndexManager indexManager,
            AXPromptLanguageProvider promptLanguageProvider,
            BooleanSupplier voiceInputGate,
            LongSupplier interruptionSignal,
            AXWorldIdentityProvider axWorldIdentityProvider,
            AXOutputSettings axOutputSettings,
            AXChatOutputSink axChatOutputSink,
            List<TianshuModuleInstaller> neoForgeInstallers,
            AXGameEventTextProvider speechTexts
    ) {
        List<TianshuModuleInstaller> installers = new ArrayList<>();
        if (neoForgeInstallers != null) {
            installers.addAll(neoForgeInstallers);
        }
        // One gate for every native model load in the process. The LLM module claims it as the primary
        // (retrieval) lane and the speech module loads as a deferred request, so the two never add their
        // native peaks together — which is what failed on a machine with an exhausted page file.
        NativeModelLoadQueue nativeModelLoadQueue = new NativeModelLoadQueue();
        installers.add(new IaModuleInstaller(moduleRuntime));
        installers.add(new LlmModuleInstaller(env, configurations.llm(), moduleRuntime, nativeModelLoadQueue));
        // One voice channel for the AX module: delegation lines and event sentences both leave through it.
        AXSpeechBridge speechBridge = new AXSpeechBridge(
                new AXProtocolSpeechOutlet(
                        new VoiceNotificationService(moduleRuntime),
                        speechTexts,
                        axOutputSettings,
                        env.logs()),
                env.logs());
        installers.add(new AXModuleInstaller(
                env,
                configurations.ax(),
                moduleRuntime,
                axWorldIdentityProvider,
                Objects.requireNonNull(promptLanguageProvider, "promptLanguageProvider"),
                assistantSettings(axOutputSettings),
                axOutputSettings,
                axChatOutputSink,
                speechBridge,
                speechBridge
        ));
        installers.add(new TtsModuleInstaller(audioBridge, moduleRuntime, env, configurations.tts(), nativeModelLoadQueue));
        // The repair lane travels with the speech module, but the game-term dictionary only the host can
        // read, so the enhancer is supplied here instead of the module reaching for it.
        installers.add(new AsrModuleInstaller(
                audioBridge,
                moduleRuntime,
                env,
                configurations.asr(),
                voiceInputGate,
                interruptionSignal,
                nativeModelLoadQueue,
                new ClientIrNamedObjectEnhancer(indexManager)
        ));
        this.delegate = new CompositeTianshuModuleAssembler(installers);
    }

    private static AXAssistantSettings assistantSettings(AXOutputSettings outputSettings) {
        return outputSettings instanceof AXAssistantSettings assistantSettings ? assistantSettings : AXAssistantSettings.DEFAULT;
    }

    @Override
    public void assemble(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        delegate.assemble(moduleHost, moduleServices);
    }
}
