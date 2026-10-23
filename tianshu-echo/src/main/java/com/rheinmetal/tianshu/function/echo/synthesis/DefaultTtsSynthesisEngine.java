package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.echo.settings.TtsConfiguration;
import com.rheinmetal.tianshu.function.echo.TtsModelService;
import com.rheinmetal.tianshu.function.echo.runtime.TtsBackendSnapshot;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;
import com.rheinmetal.tianshu.model.TtsModelInfo;

import java.util.Optional;
import java.util.List;

public final class DefaultTtsSynthesisEngine implements TtsSynthesisEngine {
    private final TtsModelService modelService;
    private final TtsModelResolver modelResolver;
    private final TtsActiveModelSelection modelSelection;
    private final TtsEngineProvider engineProvider;
    private volatile boolean initialized;
    private volatile TtsResolvedModel loadedModel;
    private volatile TtsBackend activeBackend;

    public DefaultTtsSynthesisEngine(
            IGameEnvironment env,
            TtsConfiguration config,
            TtsModelService modelService,
            TtsCodecExecution codecExecution
    ) {
        this.modelService = modelService;
        this.modelResolver = new TtsModelResolver(env, modelService);
        this.modelSelection = new TtsActiveModelSelection(config::getCustomTtsName);
        this.engineProvider = new TtsEngineProvider(env, codecExecution);
    }

    @Override
    public synchronized boolean initialize() {
        return applyModel(modelSelection.currentModelName(), false);
    }

    /**
     * Discards nothing: the replacement is loaded first and the running backend is closed only once the
     * replacement is up, so a failed reload leaves the lane exactly as it was.
     */
    @Override
    public synchronized boolean reload() {
        return applyModel(modelSelection.currentModelName(), true);
    }

    @Override
    public boolean isInitialized() {
        TtsBackend backend = activeBackend;
        return initialized && backend != null && backend.isInitialized();
    }

    @Override
    public synchronized boolean preloadVoice(com.rheinmetal.tianshu.function.echo.runtime.TtsVoiceProfile voiceProfile) {
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
    public int contextualSentenceLimit(List<String> sentences) {
        TtsBackend backend = activeBackend;
        return backend == null ? 1 : backend.contextualSentenceLimit(sentences);
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
        if (isRunning(info.name)) {
            return true;
        }
        return applyModel(info.name, false);
    }

    /**
     * Brings the backend up on {@code modelName} and adopts the model only after the load succeeded.
     *
     * <p>Adopting afterwards is what makes a rejected switch harmless: the selection, the loaded model and
     * the active backend all still describe the model that is really running, so the next synthesis — and
     * the next load attempt — start from the truth instead of from a model that was never loaded.</p>
     */
    private boolean applyModel(String modelName, boolean forceReload) {
        Optional<TtsResolvedModel> resolved = modelResolver.resolve(modelName);
        if (resolved.isEmpty()) {
            // Nothing resolvable: keep serving whatever is up rather than dropping a working model for a
            // selection that names nothing.
            return false;
        }
        TtsResolvedModel model = resolved.get();
        TtsEngineProvider.Load load = engineProvider.acquire(model, forceReload);
        return switch (load.outcome()) {
            case UNCHANGED -> {
                initialized = true;
                yield true;
            }
            case RELOADED -> {
                adoptSelection(model);
                initialized = true;
                activeBackend = load.backend();
                loadedModel = model;
                yield true;
            }
            case FAILED -> {
                // The engine kept whatever was already serving, so the state has to follow that backend
                // rather than the model that was asked for.
                boolean serving = load.backend() != null && load.backend().isInitialized();
                initialized = serving;
                activeBackend = serving ? load.backend() : null;
                if (!serving) {
                    loadedModel = null;
                }
                yield false;
            }
        };
    }

    private boolean isRunning(String modelName) {
        return initialized
                && loadedModel != null
                && loadedModel.modelInfo() != null
                && loadedModel.modelInfo().name != null
                && loadedModel.modelInfo().name.equalsIgnoreCase(modelName)
                && activeBackend != null
                && activeBackend.isInitialized();
    }

    private void adoptSelection(TtsResolvedModel model) {
        TtsModelInfo info = model.modelInfo();
        if (info != null && info.name != null && !info.name.isBlank()) {
            modelSelection.activate(info.name);
        }
    }

    @Override
    public synchronized void clearModel() {
        modelSelection.clear();
        shutdown();
    }

    @Override
    public synchronized void synthesize(TtsRequest request, TtsAudioSink sink) {
        // No lazy load here on purpose. Loading reads a native model, and that may only happen on the
        // managed path — the coordinator, under the shared permit. A synthesis that arrives before the
        // model is up reports that instead of quietly becoming the second door into native memory, which
        // would also make "is the gate in place" unobservable from the outside.
        if (!isInitialized()) {
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
