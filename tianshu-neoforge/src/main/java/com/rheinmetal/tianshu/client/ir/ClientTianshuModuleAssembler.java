package com.rheinmetal.tianshu.client.ir;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssembler;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.CompositeTianshuFunctionModuleAssembler;
import com.rheinmetal.tianshu.function.TianshuCoreModuleInstallers;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.client.rag.MinecraftAXPromptLanguageProvider;
import com.rheinmetal.tianshu.client.rag.MinecraftRuntimeFactTextResolver;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.provider.WorldStateProvider;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class ClientTianshuModuleAssembler implements TianshuModuleAssembler {
    private final CompositeTianshuFunctionModuleAssembler delegate;

    public ClientTianshuModuleAssembler(
            IGameEnvironment env,
            ITianshuConfig config,
            INativeLibBridge nativeLibBridge,
            IAudioBridge audioBridge,
            ProtocolRuntime protocolRuntime,
            BooleanSupplier voiceInputGate,
            LongSupplier interruptionSignal,
            AXWorldIdentityProvider axWorldIdentityProvider,
            WorldStateProvider worldStateProvider
    ) {
        List<TianshuFunctionModuleInstaller> installers = new java.util.ArrayList<>();
        installers.addAll(TianshuCoreModuleInstallers.clientCore(
                env,
                config,
                nativeLibBridge,
                audioBridge,
                protocolRuntime,
                voiceInputGate,
                interruptionSignal,
                axWorldIdentityProvider,
                worldStateProvider,
                new MinecraftRuntimeFactTextResolver(),
                new MinecraftAXPromptLanguageProvider(),
                new ClientIrModuleInstaller(protocolRuntime)
        ));
        this.delegate = new CompositeTianshuFunctionModuleAssembler(installers);
    }

    @Override
    public void assemble(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        delegate.assemble(moduleHost, moduleServices);
    }
}
