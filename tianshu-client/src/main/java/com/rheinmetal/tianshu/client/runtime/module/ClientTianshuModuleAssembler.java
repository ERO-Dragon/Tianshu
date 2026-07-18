package com.rheinmetal.tianshu.client.runtime.module;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.client.ir.ClientIrModuleInstaller;
import com.rheinmetal.tianshu.client.ir.ClientNamedObjectIndexManager;
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
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityCoreAdapter;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.ia.IaModuleInstaller;
import com.rheinmetal.tianshu.function.llm.LlmModuleInstaller;
import com.rheinmetal.tianshu.function.tts.TtsModuleInstaller;
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
        List<TianshuModuleInstaller> installers = new ArrayList<>();
        if (neoForgeInstallers != null) {
            installers.addAll(neoForgeInstallers);
        }
        installers.add(new IaModuleInstaller(moduleRuntime));
        installers.add(new ClientIrModuleInstaller(moduleRuntime, indexManager));
        installers.add(new LlmModuleInstaller(
                env,
                configurations.llm(),
                moduleRuntime,
                axWorldIdentityProvider == null ? null : new AXWorldIdentityCoreAdapter(axWorldIdentityProvider)
        ));
        installers.add(new AXModuleInstaller(
                env,
                configurations.ax(),
                moduleRuntime,
                axWorldIdentityProvider,
                Objects.requireNonNull(promptLanguageProvider, "promptLanguageProvider"),
                assistantSettings(axOutputSettings),
                axOutputSettings,
                axChatOutputSink
        ));
        installers.add(new TtsModuleInstaller(audioBridge, moduleRuntime, env, configurations.tts()));
        installers.add(new AsrModuleInstaller(
                audioBridge,
                moduleRuntime,
                env,
                configurations.asr(),
                voiceInputGate,
                interruptionSignal
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
