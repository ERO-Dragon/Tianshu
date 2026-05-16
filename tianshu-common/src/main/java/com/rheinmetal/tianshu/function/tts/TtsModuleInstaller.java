package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.function.LongSupplier;

public final class TtsModuleInstaller implements TianshuFunctionModuleInstaller {
    private final IAudioBridge audioBridge;
    private final TianshuEventBus eventBus;
    private final ProtocolRuntime protocolRuntime;
    private final IGameEnvironment env;
    private final ITianshuConfig config;

    public TtsModuleInstaller(IAudioBridge audioBridge, TianshuEventBus eventBus, ProtocolRuntime protocolRuntime, IGameEnvironment env, ITianshuConfig config, LongSupplier interruptionSignal) {
        this.audioBridge = audioBridge;
        this.eventBus = eventBus;
        this.protocolRuntime = protocolRuntime;
        this.env = env;
        this.config = config;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new TtsModule(audioBridge, eventBus, protocolRuntime, env, config), TtsRuntimeCapabilities.SYNTHESIS, TtsRuntimeCapabilities.PLAYBACK);
    }
}
