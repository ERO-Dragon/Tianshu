package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.model.lifecycle.NativeModelLoadQueue;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class AsrModuleInstaller implements TianshuModuleInstaller {
    private final IAudioBridge audioBridge;
    private final ModuleRuntimeAccess moduleRuntime;
    private final IGameEnvironment env;
    private final AsrConfiguration config;
    private final BooleanSupplier voiceInputGate;
    private final LongSupplier interruptionSignal;
    /** Serial gate shared with the retrieval and speech lanes; null installs the module without one. */
    private final NativeModelLoadQueue loadQueue;

    public AsrModuleInstaller(IAudioBridge audioBridge, ModuleRuntimeAccess moduleRuntime, IGameEnvironment env, AsrConfiguration config, BooleanSupplier voiceInputGate, LongSupplier interruptionSignal) {
        this(audioBridge, moduleRuntime, env, config, voiceInputGate, interruptionSignal, null);
    }

    public AsrModuleInstaller(IAudioBridge audioBridge, ModuleRuntimeAccess moduleRuntime, IGameEnvironment env,
                              AsrConfiguration config, BooleanSupplier voiceInputGate,
                              LongSupplier interruptionSignal, NativeModelLoadQueue loadQueue) {
        this.audioBridge = audioBridge;
        this.moduleRuntime = moduleRuntime;
        this.env = env;
        this.config = config;
        this.voiceInputGate = voiceInputGate;
        this.interruptionSignal = interruptionSignal;
        this.loadQueue = loadQueue;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(
                new AsrModule(audioBridge, moduleRuntime, env, config, voiceInputGate, interruptionSignal, loadQueue),
                AsrRuntimeCapabilities.INPUT,
                AsrRuntimeCapabilities.MODEL
        );
    }
}
