package com.rheinmetal.tianshu.function;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.auxilium.AXModuleInstaller;
import com.rheinmetal.tianshu.function.auxilium.AXAssistantSettings;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityCoreAdapter;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.asr.AsrModuleInstaller;
import com.rheinmetal.tianshu.function.ia.IaModuleInstaller;
import com.rheinmetal.tianshu.function.llm.LlmModuleInstaller;
import com.rheinmetal.tianshu.function.tts.TtsModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class TianshuCoreModuleInstallers {
    private TianshuCoreModuleInstallers() {
    }

    public static List<TianshuFunctionModuleInstaller> clientCore(
            IGameEnvironment env,
            TianshuFunctionConfigurations configurations,
            IAudioBridge audioBridge,
            ModuleRuntimeAccess moduleRuntime,
            BooleanSupplier voiceInputGate,
            LongSupplier interruptionSignal,
            AXWorldIdentityProvider axWorldIdentityProvider,
            TianshuFunctionModuleInstaller irInstaller
    ) {
        return clientCore(
                env,
                configurations,
                audioBridge,
                moduleRuntime,
                voiceInputGate,
                interruptionSignal,
                axWorldIdentityProvider,
                null,
                irInstaller
        );
    }

    public static List<TianshuFunctionModuleInstaller> clientCore(
            IGameEnvironment env,
            TianshuFunctionConfigurations configurations,
            IAudioBridge audioBridge,
            ModuleRuntimeAccess moduleRuntime,
            BooleanSupplier voiceInputGate,
            LongSupplier interruptionSignal,
            AXWorldIdentityProvider axWorldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            TianshuFunctionModuleInstaller irInstaller
    ) {
        return clientCore(
                env,
                configurations,
                audioBridge,
                moduleRuntime,
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
            TianshuFunctionConfigurations configurations,
            IAudioBridge audioBridge,
            ModuleRuntimeAccess moduleRuntime,
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
                configurations,
                audioBridge,
                moduleRuntime,
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
            TianshuFunctionConfigurations configurations,
            IAudioBridge audioBridge,
            ModuleRuntimeAccess moduleRuntime,
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
                ? moduleHostInstaller(moduleRuntime)
                : irInstaller;
        return List.of(
                new IaModuleInstaller(moduleRuntime),
                effectiveIrInstaller,
                new LlmModuleInstaller(env, configurations.llm(), moduleRuntime, axWorldIdentityProvider == null ? null : new AXWorldIdentityCoreAdapter(axWorldIdentityProvider)),
                new AXModuleInstaller(env, configurations.ax(), moduleRuntime, axWorldIdentityProvider, promptLanguageProvider, axAssistantSettings, axOutputSettings, axChatOutputSink),
                new TtsModuleInstaller(audioBridge, moduleRuntime, env, configurations.tts()),
                new AsrModuleInstaller(audioBridge, moduleRuntime, env, configurations.asr(), voiceInputGate, interruptionSignal)
        );
    }

    private static TianshuFunctionModuleInstaller moduleHostInstaller(ModuleRuntimeAccess moduleRuntime) {
        return (moduleHost, moduleServices) -> moduleHost.registerOptionalModule(new com.rheinmetal.tianshu.function.ir.IrModule(moduleRuntime));
    }
}
