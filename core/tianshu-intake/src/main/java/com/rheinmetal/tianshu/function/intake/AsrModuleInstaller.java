package com.rheinmetal.tianshu.function.intake;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.intake.settings.IntakeConfiguration;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.function.intake.repair.AsrTextRepairer;
import com.rheinmetal.tianshu.function.intake.repair.enhance.IrNamedObjectEnhancer;
import com.rheinmetal.tianshu.model.NativeModelLoadQueue;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class AsrModuleInstaller implements TianshuModuleInstaller {
    private final IAudioBridge audioBridge;
    private final ModuleRuntimeAccess moduleRuntime;
    private final IGameEnvironment env;
    private final IntakeConfiguration config;
    private final BooleanSupplier voiceInputGate;
    private final LongSupplier interruptionSignal;
    /** Serial gate shared with the retrieval and speech lanes; null installs the module without one. */
    private final NativeModelLoadQueue loadQueue;
    /** Game-term repair supplied by the host; null restricts the repair lane to voice triggers. */
    private final IrNamedObjectEnhancer namedObjectEnhancer;

    public AsrModuleInstaller(IAudioBridge audioBridge, ModuleRuntimeAccess moduleRuntime, IGameEnvironment env, IntakeConfiguration config, BooleanSupplier voiceInputGate, LongSupplier interruptionSignal) {
        this(audioBridge, moduleRuntime, env, config, voiceInputGate, interruptionSignal, null, null);
    }

    public AsrModuleInstaller(IAudioBridge audioBridge, ModuleRuntimeAccess moduleRuntime, IGameEnvironment env,
                              IntakeConfiguration config, BooleanSupplier voiceInputGate,
                              LongSupplier interruptionSignal, NativeModelLoadQueue loadQueue) {
        this(audioBridge, moduleRuntime, env, config, voiceInputGate, interruptionSignal, loadQueue, null);
    }

    /**
     * @param namedObjectEnhancer repairs game terms in the recognised text; supplied by the host because
     *                            only it can read the game's object registry
     */
    public AsrModuleInstaller(IAudioBridge audioBridge, ModuleRuntimeAccess moduleRuntime, IGameEnvironment env,
                              IntakeConfiguration config, BooleanSupplier voiceInputGate,
                              LongSupplier interruptionSignal, NativeModelLoadQueue loadQueue,
                              IrNamedObjectEnhancer namedObjectEnhancer) {
        this.audioBridge = audioBridge;
        this.moduleRuntime = moduleRuntime;
        this.env = env;
        this.config = config;
        this.voiceInputGate = voiceInputGate;
        this.interruptionSignal = interruptionSignal;
        this.loadQueue = loadQueue;
        this.namedObjectEnhancer = namedObjectEnhancer;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(
                new AsrModule(audioBridge, moduleRuntime, env, config, voiceInputGate, interruptionSignal,
                        loadQueue, namedObjectEnhancer),
                AsrRuntimeCapabilities.INPUT,
                AsrRuntimeCapabilities.MODEL
        );
    }
}
