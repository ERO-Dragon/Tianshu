package com.rheinmetal.tianshu.function.tts.synthesis;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.tts.TtsModelService;
import com.rheinmetal.tianshu.function.tts.runtime.TtsBackendSnapshot;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequest;
import com.rheinmetal.tianshu.model.TtsModelInfo;

import java.util.Optional;

public final class DefaultTtsSynthesisEngine implements TtsSynthesisEngine {
    private final IGameEnvironment env;
    private final TtsModelService modelService;
    private final TtsModelResolver modelResolver;
    private final TtsEngineProvider engineProvider;
    private volatile boolean initialized;
    private volatile TtsResolvedModel loadedModel;
    private volatile TtsBackend activeBackend;

    public DefaultTtsSynthesisEngine(IGameEnvironment env, ITianshuConfig config, TtsModelService modelService) {
        this.env = env;
        this.modelService = modelService;
        this.modelResolver = new TtsModelResolver(env, modelService);
        this.engineProvider = new TtsEngineProvider(env, config);
    }

    @Override
    public synchronized boolean initialize() {
        Optional<TtsResolvedModel> resolved = modelResolver.resolveCurrent();
        if (resolved.isEmpty()) {
            initialized = false;
            activeBackend = null;
            loadedModel = null;
            return false;
        }
        TtsResolvedModel model = resolved.get();
        TtsBackend backend = engineProvider.acquire(model);
        initialized = backend.isInitialized();
        activeBackend = initialized ? backend : null;
        loadedModel = initialized ? model : null;
        return initialized;
    }

    @Override
    public boolean isInitialized() {
        TtsBackend backend = activeBackend;
        return initialized && backend != null && backend.isInitialized();
    }

    @Override
    public boolean isAutoregressive() {
        TtsResolvedModel model = loadedModel;
        if (model != null) {
            return model.autoregressive();
        }
        Optional<TtsResolvedModel> resolved = modelResolver.resolveCurrent();
        return resolved.map(TtsResolvedModel::autoregressive).orElse(false);
    }

    @Override
    public int sampleRate() {
        TtsBackend backend = activeBackend;
        return backend == null ? 0 : backend.sampleRate();
    }

    @Override
    public TtsBackendSnapshot backendSnapshot() {
        TtsResolvedModel model = loadedModel;
        TtsBackend backend = activeBackend;
        if (model == null) {
            Optional<TtsResolvedModel> resolved = modelResolver.resolveCurrent();
            if (resolved.isEmpty()) {
                return TtsBackendSnapshot.unavailable();
            }
            TtsResolvedModel resolvedModel = resolved.get();
            return new TtsBackendSnapshot(
                    true,
                    false,
                    resolvedModel.backendType(),
                    resolvedModel.engineType(),
                    resolvedModel.autoregressive(),
                    0,
                    resolvedModel.modelDir().toString(),
                    System.currentTimeMillis()
            );
        }
        return new TtsBackendSnapshot(
                true,
                backend != null && backend.isInitialized(),
                model.backendType(),
                model.engineType(),
                model.autoregressive(),
                backend == null ? 0 : backend.sampleRate(),
                model.modelDir().toString(),
                System.currentTimeMillis()
        );
    }

    @Override
    public synchronized boolean useModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        TtsModelInfo info = modelService.findModelByName(modelName);
        if (info == null) {
            return false;
        }
        modelService.useModel(info.name);
        shutdown();
        return initialize();
    }

    @Override
    public synchronized void synthesize(TtsRequest request, TtsAudioSink sink) {
        if (!initialize()) {
            env.warn("TTS 引擎不可用，跳过合成: " + request.text());
            return;
        }
        TtsBackend backend = activeBackend;
        if (backend == null) {
            env.warn("TTS 引擎未激活，跳过合成: " + request.text());
            return;
        }
        backend.synthesize(request, sink);
    }

    @Override
    public void interrupt() {
        TtsBackend backend = activeBackend;
        if (backend != null) {
            backend.interrupt();
        }
    }

    @Override
    public synchronized void shutdown() {
        initialized = false;
        loadedModel = null;
        activeBackend = null;
        engineProvider.shutdown();
    }
}
