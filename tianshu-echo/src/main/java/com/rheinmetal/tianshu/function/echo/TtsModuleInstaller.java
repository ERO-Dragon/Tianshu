package com.rheinmetal.tianshu.function.echo;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.echo.settings.TtsConfiguration;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.model.lifecycle.NativeModelLoadQueue;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

public final class TtsModuleInstaller implements TianshuModuleInstaller {
    private final IAudioBridge audioBridge;
    private final ModuleRuntimeAccess moduleRuntime;
    private final IGameEnvironment env;
    private final TtsConfiguration config;
    /** Serial gate shared with the retrieval lane; null installs the module without one. */
    private final NativeModelLoadQueue loadQueue;

    public TtsModuleInstaller(IAudioBridge audioBridge, ModuleRuntimeAccess moduleRuntime, IGameEnvironment env, TtsConfiguration config) {
        this(audioBridge, moduleRuntime, env, config, null);
    }

    public TtsModuleInstaller(IAudioBridge audioBridge, ModuleRuntimeAccess moduleRuntime, IGameEnvironment env,
                              TtsConfiguration config, NativeModelLoadQueue loadQueue) {
        this.audioBridge = audioBridge;
        this.moduleRuntime = moduleRuntime;
        this.env = env;
        this.config = config;
        this.loadQueue = loadQueue;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(
                new TtsModule(audioBridge, moduleRuntime, env, config, loadQueue),
                TtsRuntimeCapabilities.SYNTHESIS,
                TtsRuntimeCapabilities.PLAYBACK,
                TtsRuntimeCapabilities.VOICE_LIBRARY,
                TtsRuntimeCapabilities.MODEL_MANAGEMENT
        );
    }
}
