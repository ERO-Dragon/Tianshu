package com.rheinmetal.tianshu.function;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.auxilium.AXModuleInstaller;
import com.rheinmetal.tianshu.function.auxilium.AXAssistantSettings;
import com.rheinmetal.tianshu.function.auxilium.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityCoreAdapter;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.asr.AsrModuleInstaller;
import com.rheinmetal.tianshu.function.ia.IaModuleInstaller;
import com.rheinmetal.tianshu.function.llm.LlmModuleInstaller;
import com.rheinmetal.tianshu.function.tts.TtsModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

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
            AXPromptLanguageProvider promptLanguageProvider,
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
                promptLanguageProvider,
                AXAssistantSettings.DEFAULT,
                AXOutputSettings.DEFAULT,
                AXChatOutputSink.NOOP,
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
            AXPromptLanguageProvider promptLanguageProvider,
            AXOutputSettings axOutputSettings,
            AXChatOutputSink axChatOutputSink,
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
                promptLanguageProvider,
                AXAssistantSettings.DEFAULT,
                axOutputSettings,
                axChatOutputSink,
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
            AXPromptLanguageProvider promptLanguageProvider,
            AXAssistantSettings axAssistantSettings,
            AXOutputSettings axOutputSettings,
            AXChatOutputSink axChatOutputSink,
            TianshuFunctionModuleInstaller irInstaller
    ) {
        TianshuFunctionModuleInstaller effectiveIrInstaller = irInstaller == null
                ? moduleHostInstaller(protocolRuntime)
                : irInstaller;
        return List.of(
                new IaModuleInstaller(protocolRuntime),
                effectiveIrInstaller,
                new LlmModuleInstaller(env, config, protocolRuntime, axWorldIdentityProvider == null ? null : new AXWorldIdentityCoreAdapter(axWorldIdentityProvider)),
                new AXModuleInstaller(env, config, protocolRuntime, axWorldIdentityProvider, promptLanguageProvider, axAssistantSettings, axOutputSettings, axChatOutputSink),
                new TtsModuleInstaller(audioBridge, protocolRuntime, env, config),
                new AsrModuleInstaller(audioBridge, protocolRuntime, env, config, voiceInputGate, interruptionSignal)
        );
    }

    private static TianshuFunctionModuleInstaller moduleHostInstaller(ProtocolRuntime protocolRuntime) {
        return (moduleHost, moduleServices) -> moduleHost.registerOptionalModule(new com.rheinmetal.tianshu.function.ir.IrModule(protocolRuntime));
    }
}
