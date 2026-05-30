package com.rheinmetal.tianshu.function;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.auxilium.AXModuleInstaller;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.rag.RuntimeFactTextResolver;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.asr.AsrModuleInstaller;
import com.rheinmetal.tianshu.function.ia.IaModuleInstaller;
import com.rheinmetal.tianshu.function.llm.LlmModuleInstaller;
import com.rheinmetal.tianshu.function.tts.AXSpeechBridgeInstaller;
import com.rheinmetal.tianshu.function.tts.TtsModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.provider.WorldStateProvider;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class TianshuCoreModuleInstallers {
    private TianshuCoreModuleInstallers() {
    }

    public static List<TianshuFunctionModuleInstaller> clientCore(
            IGameEnvironment env,
            ITianshuConfig config,
            IAudioBridge audioBridge,
            ProtocolRuntime protocolRuntime,
            BooleanSupplier voiceInputGate,
            LongSupplier interruptionSignal,
            AXWorldIdentityProvider axWorldIdentityProvider,
            WorldStateProvider worldStateProvider,
            TianshuFunctionModuleInstaller irInstaller
    ) {
        return clientCore(
                env,
                config,
                audioBridge,
                protocolRuntime,
                voiceInputGate,
                interruptionSignal,
                axWorldIdentityProvider,
                worldStateProvider,
                null,
                null,
                irInstaller
        );
    }

    public static List<TianshuFunctionModuleInstaller> clientCore(
            IGameEnvironment env,
            ITianshuConfig config,
            IAudioBridge audioBridge,
            ProtocolRuntime protocolRuntime,
            BooleanSupplier voiceInputGate,
            LongSupplier interruptionSignal,
            AXWorldIdentityProvider axWorldIdentityProvider,
            WorldStateProvider worldStateProvider,
            RuntimeFactTextResolver runtimeFactTextResolver,
            AXPromptLanguageProvider promptLanguageProvider,
            TianshuFunctionModuleInstaller irInstaller
    ) {
        TianshuFunctionModuleInstaller effectiveIrInstaller = irInstaller == null
                ? moduleHostInstaller(protocolRuntime)
                : irInstaller;
        return List.of(
                new IaModuleInstaller(protocolRuntime),
                effectiveIrInstaller,
                new LlmModuleInstaller(env, config, protocolRuntime),
                new AXModuleInstaller(env, config, protocolRuntime, axWorldIdentityProvider, worldStateProvider, runtimeFactTextResolver, promptLanguageProvider),
                new TtsModuleInstaller(audioBridge, protocolRuntime, env, config),
                new AXSpeechBridgeInstaller(protocolRuntime),
                new AsrModuleInstaller(audioBridge, protocolRuntime, env, config, voiceInputGate, interruptionSignal)
        );
    }

    private static TianshuFunctionModuleInstaller moduleHostInstaller(ProtocolRuntime protocolRuntime) {
        return (moduleHost, moduleServices) -> moduleHost.registerOptionalModule(new com.rheinmetal.tianshu.function.ir.IrModule(protocolRuntime));
    }
}