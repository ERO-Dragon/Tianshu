package com.rheinmetal.tianshu.function.tts.synthesis;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.settings.TtsConfiguration;
import com.rheinmetal.tianshu.function.tts.TtsModelService;
import com.rheinmetal.tianshu.function.tts.runtime.TtsBackendSnapshot;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequest;
import com.rheinmetal.tianshu.model.TtsModelInfo;

import java.util.Optional;

public final class DefaultTtsSynthesisEngine implements TtsSynthesisEngine {
    private final IGameEnvironment env;
    private final TtsModelService modelService;
    private final TtsModelResolver modelResolver;
    private final TtsActiveModelSelection modelSelection;
    private final TtsEngineProvider engineProvider;
    private volatile boolean initialized;
    private volatile TtsResolvedModel loadedModel;
    private volatile TtsBackend activeBackend;

    public DefaultTtsSynthesisEngine(IGameEnvironment env, TtsConfiguration config, TtsModelService modelService) {
        this.env = env;
        this.modelService = modelService;
        this.modelResolver = new TtsModelResolver(env, modelService);
        this.modelSelection = new TtsActiveModelSelection(config::getCustomTtsName);
        this.engineProvider = new TtsEngineProvider(env);
    }

    @Override
    public synchronized boolean initialize() {
        Optional<TtsResolvedModel> resolved = modelResolver.resolve(modelSelection.currentModelName());
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
    public synchronized boolean preloadVoice(com.rheinmetal.tianshu.function.tts.runtime.TtsVoiceProfile voiceProfile) {
        TtsBackend backend = activeBackend;
        return backend != null && backend.preloadVoice(voiceProfile);
    }

    @Override
    public boolean isAutoregressive() {
        TtsResolvedModel model = loadedModel;
        if (model != null) {
            return model.autoregressive();
        }
        Optional<TtsResolvedModel> resolved = modelResolver.resolve(modelSelection.currentModelName());
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
            Optional<TtsResolvedModel> resolved = modelResolver.resolve(modelSelection.currentModelName());
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
        modelSelection.activate(info.name);
        shutdown();
        return initialize();
    }

    @Override
    public synchronized void clearModel() {
        modelSelection.clear();
        shutdown();
    }

    @Override
    public synchronized void synthesize(TtsRequest request, TtsAudioSink sink) {
        if (!initialize()) {
            throw new IllegalStateException("TTS synthesis engine is unavailable");
        }
        TtsBackend backend = activeBackend;
        if (backend == null) {
            throw new IllegalStateException("TTS backend is not active");
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
