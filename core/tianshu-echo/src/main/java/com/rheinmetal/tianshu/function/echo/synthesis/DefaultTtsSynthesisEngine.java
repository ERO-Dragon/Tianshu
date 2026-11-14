package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.echo.settings.EchoConfiguration;
import com.rheinmetal.tianshu.function.echo.synthesis.cloud.TtsCloudSettings;
import com.rheinmetal.tianshu.function.echo.TtsModelService;
import com.rheinmetal.tianshu.function.echo.runtime.TtsBackendSnapshot;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;
import com.rheinmetal.tianshu.function.echo.model.TtsModelInfo;

import java.util.Optional;
import java.util.List;

public final class DefaultTtsSynthesisEngine implements TtsSynthesisEngine {
    private final TtsModelService modelService;
    private final EchoConfiguration config;
    private final TtsModelResolver modelResolver;
    private final TtsActiveModelSelection modelSelection;
    private final TtsEngineProvider engineProvider;
    private volatile boolean initialized;
    private volatile TtsBackendSelection loadedSelection;
    private volatile TtsBackend activeBackend;

    public DefaultTtsSynthesisEngine(
            IGameEnvironment env,
            EchoConfiguration config,
            TtsModelService modelService,
            TtsCodecExecution codecExecution
    ) {
        this.modelService = modelService;
        this.config = config;
        this.modelResolver = new TtsModelResolver(env, modelService);
        this.modelSelection = new TtsActiveModelSelection(config::getCustomTtsName);
        this.engineProvider = new TtsEngineProvider(env, codecExecution);
    }

    @Override
    public synchronized boolean initialize() {
        return applyCurrentSelection(false);
    }

    /**
     * Discards nothing: the replacement is loaded first and the running backend is closed only once the
     * replacement is up, so a failed reload leaves the lane exactly as it was.
     */
    @Override
    public synchronized boolean reload() {
        return applyCurrentSelection(true);
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
        TtsBackendSelection selection = loadedSelection;
        if (selection != null) {
            return selection.backendType().autoregressive();
        }
        return selectionFor(modelSelection.currentModelName())
                .map(resolved -> resolved.backendType().autoregressive())
                .orElse(false);
    }

    @Override
    public int sampleRate() {
        TtsBackend backend = activeBackend;
        return backend == null ? 0 : backend.sampleRate();
    }

    @Override
    public TtsBackendSnapshot backendSnapshot() {
        TtsBackendSelection selection = loadedSelection;
        TtsBackend backend = activeBackend;
        if (selection == null) {
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
        TtsResolvedModel localModel = selection instanceof LocalTtsBackendSelection local ? local.model() : null;
        return new TtsBackendSnapshot(
                true,
                backend != null && backend.isInitialized(),
                selection.backendType(),
                localModel == null ? "cloud" : localModel.engineType(),
                selection.backendType().autoregressive(),
                backend == null ? 0 : backend.sampleRate(),
                localModel == null ? "" : localModel.modelDir().toString(),
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
        return useSelection(selectionFor(modelName).orElse(null));
    }

    /**
     * Switches to an explicit selection.
     *
     * <p>Unlike {@link #useModel(String)} this does not consult the catalog: the caller already knows what it
     * wants (the coordinator carries a selection through a whole switch, and a preview carries the one that
     * will be restored). Resolving a name again here would let the two disagree.</p>
     */
    @Override
    public synchronized boolean useSelection(TtsBackendSelection selection) {
        if (selection == null) {
            return false;
        }
        if (isRunning(selection)) {
            return true;
        }
        return applySelection(selection, false);
    }

    @Override
    public Optional<TtsBackendSelection> selectionFor(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        return modelResolver.resolve(modelName).map(LocalTtsBackendSelection::new);
    }

    @Override
    public String activeModelName() {
        return modelSelection.currentModelName();
    }

    /**
     * Brings the backend up on {@code modelName} and adopts the model only after the load succeeded.
     *
     * <p>Adopting afterwards is what makes a rejected switch harmless: the selection, the loaded model and
     * the active backend all still describe the model that is really running, so the next synthesis — and
     * the next load attempt — start from the truth instead of from a model that was never loaded.</p>
     */
    /** Resolves the selection the player's settings currently describe. */
    private Optional<TtsBackendSelection> currentSelection() {
        if (TtsCloudSettings.isCloudMode(config)) {
            return Optional.of(new CloudTtsBackendSelection(TtsCloudSettings.from(config).toProfile()));
        }
        return selectionFor(modelSelection.currentModelName());
    }

    private boolean applyCurrentSelection(boolean forceReload) {
        Optional<TtsBackendSelection> selection = currentSelection();
        if (selection.isEmpty()) {
            // Nothing resolvable: keep serving whatever is up rather than dropping a working backend for a
            // selection that names nothing.
            return false;
        }
        return applySelection(selection.get(), forceReload);
    }

    /**
     * Brings the backend up on {@code selection} and adopts it only after the load succeeded.
     *
     * <p>Adopting afterwards is what makes a rejected switch harmless: the loaded selection and the active
     * backend still describe what is really running, so the next synthesis — and the next load attempt —
     * start from the truth instead of from something that was never loaded.</p>
     */
    private boolean applySelection(TtsBackendSelection selection, boolean forceReload) {
        TtsEngineProvider.Load load = engineProvider.acquire(selection, forceReload);
        return switch (load.outcome()) {
            case UNCHANGED -> {
                initialized = true;
                yield true;
            }
            case RELOADED -> {
                adoptSelection(selection);
                initialized = true;
                activeBackend = load.backend();
                loadedSelection = selection;
                yield true;
            }
            case FAILED -> {
                // The engine kept whatever was already serving, so the state has to follow that backend
                // rather than the model that was asked for.
                boolean serving = load.backend() != null && load.backend().isInitialized();
                initialized = serving;
                activeBackend = serving ? load.backend() : null;
                if (!serving) {
                    loadedSelection = null;
                }
                yield false;
            }
        };
    }

    private boolean isRunning(TtsBackendSelection selection) {
        TtsBackendSelection loaded = loadedSelection;
        return initialized
                && loaded != null
                && loaded.equals(selection)
                && activeBackend != null
                && activeBackend.isInitialized();
    }

    private void adoptSelection(TtsBackendSelection selection) {
        TtsModelInfo info = selection instanceof LocalTtsBackendSelection local ? local.model().modelInfo() : null;
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
        loadedSelection = null;
        activeBackend = null;
        engineProvider.shutdown();
    }
}
