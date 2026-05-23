package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class TtsModuleInstaller implements TianshuFunctionModuleInstaller {
    private final IAudioBridge audioBridge;
    private final ProtocolRuntime protocolRuntime;
    private final IGameEnvironment env;
    private final ITianshuConfig config;

    public TtsModuleInstaller(IAudioBridge audioBridge, ProtocolRuntime protocolRuntime, IGameEnvironment env, ITianshuConfig config) {
        this.audioBridge = audioBridge;
        this.protocolRuntime = protocolRuntime;
        this.env = env;
        this.config = config;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new TtsModule(audioBridge, protocolRuntime, env, config), TtsRuntimeCapabilities.SYNTHESIS, TtsRuntimeCapabilities.PLAYBACK);
    }
}
