package com.rheinmetal.tianshu.function.tts.synthesis;

import com.rheinmetal.tianshu.api.IGameEnvironment;

public final class TtsEngineProvider {
    private final IGameEnvironment env;
    private TtsBackend backend;
    private TtsResolvedModel loadedModel;

    public TtsEngineProvider(IGameEnvironment env) {
        this.env = env;
    }

    public synchronized TtsBackend acquire(TtsResolvedModel model) {
        if (backend != null && backend.isInitialized() && sameModel(model)) {
            return backend;
        }
        shutdown();
        backend = createBackend(model.backendType());
        boolean initialized = backend.initialize(model);
        loadedModel = initialized ? model : null;
        return backend;
    }

    public synchronized TtsResolvedModel loadedModel() {
        return loadedModel;
    }

    public synchronized void shutdown() {
        if (backend != null) {
            backend.shutdown();
            backend = null;
        }
        loadedModel = null;
    }

    private TtsBackend createBackend(TtsBackendType backendType) {
        return switch (backendType) {
            case MOSS -> new MossTtsBackend(env);
            case SHERPA -> new SherpaOnnxTtsBackend(env);
        };
    }

    private boolean sameModel(TtsResolvedModel model) {
        return loadedModel != null
                && loadedModel.modelDir().equals(model.modelDir())
                && loadedModel.engineType().equals(model.engineType())
                && loadedModel.backendType() == model.backendType();
    }
}
