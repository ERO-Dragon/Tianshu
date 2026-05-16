package com.rheinmetal.tianshu.function;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.function.assistant.AssistantModuleInstaller;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantWorldIdentityProvider;
import com.rheinmetal.tianshu.function.asr.AsrModuleInstaller;
import com.rheinmetal.tianshu.function.ia.IaModuleInstaller;
import com.rheinmetal.tianshu.function.llm.LlmModuleInstaller;
import com.rheinmetal.tianshu.function.tts.AssistantSpeechBridgeInstaller;
import com.rheinmetal.tianshu.function.tts.TtsModuleInstaller;
import com.rheinmetal.tianshu.function.ui.UiProtocolBridgeInstaller;
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
            INativeLibBridge nativeLibBridge,
            IAudioBridge audioBridge,
            TianshuEventBus eventBus,
            ProtocolRuntime protocolRuntime,
            BooleanSupplier voiceInputGate,
            LongSupplier interruptionSignal,
            AssistantWorldIdentityProvider assistantWorldIdentityProvider,
            WorldStateProvider worldStateProvider,
            TianshuFunctionModuleInstaller irInstaller
    ) {
        TianshuFunctionModuleInstaller effectiveIrInstaller = irInstaller == null
                ? moduleHostInstaller(protocolRuntime)
                : irInstaller;
        return List.of(
                new IaModuleInstaller(protocolRuntime),
                effectiveIrInstaller,
                new LlmModuleInstaller(env, config, nativeLibBridge, protocolRuntime),
                new AssistantModuleInstaller(env, config, protocolRuntime, assistantWorldIdentityProvider, worldStateProvider),
                new TtsModuleInstaller(audioBridge, eventBus, protocolRuntime, env, config, interruptionSignal),
                new AssistantSpeechBridgeInstaller(protocolRuntime),
                new AsrModuleInstaller(audioBridge, protocolRuntime, env, config, voiceInputGate, interruptionSignal),
                new UiProtocolBridgeInstaller(protocolRuntime, eventBus)
        );
    }

    private static TianshuFunctionModuleInstaller moduleHostInstaller(ProtocolRuntime protocolRuntime) {
        return (moduleHost, moduleServices) -> moduleHost.registerOptionalModule(new com.rheinmetal.tianshu.function.ir.IrModule(protocolRuntime));
    }
}
