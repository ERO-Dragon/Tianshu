package com.rheinmetal.tianshu.client.lifecycle;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.client.ir.ClientIrModuleInstaller;
import com.rheinmetal.tianshu.client.ir.ClientNamedObjectIndexManager;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssembler;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.CompositeTianshuFunctionModuleAssembler;
import com.rheinmetal.tianshu.function.TianshuCoreModuleInstallers;
import com.rheinmetal.tianshu.function.TianshuFunctionConfigurations;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.function.auxilium.AXAssistantSettings;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class ClientTianshuModuleAssembler implements TianshuModuleAssembler {
    private final CompositeTianshuFunctionModuleAssembler delegate;

    public ClientTianshuModuleAssembler(
            IGameEnvironment env,
            TianshuFunctionConfigurations configurations,
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
            TianshuFunctionConfigurations configurations,
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
            TianshuFunctionConfigurations configurations,
            IAudioBridge audioBridge,
            ModuleRuntimeAccess moduleRuntime,
            ClientNamedObjectIndexManager indexManager,
            AXPromptLanguageProvider promptLanguageProvider,
            BooleanSupplier voiceInputGate,
            LongSupplier interruptionSignal,
            AXWorldIdentityProvider axWorldIdentityProvider,
            AXOutputSettings axOutputSettings,
            AXChatOutputSink axChatOutputSink,
            List<TianshuFunctionModuleInstaller> neoForgeInstallers
    ) {
        List<TianshuFunctionModuleInstaller> installers = new java.util.ArrayList<>();
        if (neoForgeInstallers != null) {
            installers.addAll(neoForgeInstallers);
        }
        installers.addAll(TianshuCoreModuleInstallers.clientCore(
                env,
                configurations,
                audioBridge,
                moduleRuntime,
                voiceInputGate,
                interruptionSignal,
                axWorldIdentityProvider,
                java.util.Objects.requireNonNull(promptLanguageProvider, "promptLanguageProvider"),
                assistantSettings(axOutputSettings),
                axOutputSettings,
                axChatOutputSink,
                new ClientIrModuleInstaller(moduleRuntime, indexManager)
        ));
        this.delegate = new CompositeTianshuFunctionModuleAssembler(installers);
    }

    private static AXAssistantSettings assistantSettings(AXOutputSettings outputSettings) {
        return outputSettings instanceof AXAssistantSettings assistantSettings ? assistantSettings : AXAssistantSettings.DEFAULT;
    }

    @Override
    public void assemble(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        delegate.assemble(moduleHost, moduleServices);
    }
}
