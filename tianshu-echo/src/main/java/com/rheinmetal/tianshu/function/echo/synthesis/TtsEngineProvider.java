package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.api.IGameEnvironment;

/**
 * Owns the one live TTS backend, and the model that backend was built for.
 *
 * <p>A switch builds the replacement <em>before</em> dropping what is running: the old backend keeps
 * serving until the new one is initialized, and a failed load therefore leaves the lane with the model it
 * already had instead of silently taking voice away until the next world. The two backends are resident
 * together for the length of that load, which is the same trade the ASR hotword reload makes — the
 * native-load gate serialises loads, and a swap has to overlap to avoid an empty window.</p>
 *
 * <p>This is the only place a backend is constructed, and {@code backend.initialize} is called exactly
 * once, in here. The coordinator decides <em>when</em> a switch may run; this class only decides whether
 * the switch succeeded.</p>
 */
public final class TtsEngineProvider {

    /** What a load attempt left the lane with. */
    public enum Outcome {
        /** The requested model was already running; nothing was loaded and nothing was closed. */
        UNCHANGED,
        /** A new backend is now in charge, and the previous one has been closed. */
        RELOADED,
        /** The candidate failed to initialize; nothing about the previous backend was changed. */
        FAILED
    }

    /** The backend in charge after an attempt, plus how the attempt ended. */
    public record Load(TtsBackend backend, Outcome outcome) {
    }

    private final IGameEnvironment env;
    private final TtsCodecExecution codecExecution;
    private TtsBackend backend;
    private TtsResolvedModel loadedModel;

    public TtsEngineProvider(IGameEnvironment env, TtsCodecExecution codecExecution) {
        this.env = env;
        this.codecExecution = codecExecution;
    }

    /**
     * Brings {@code model} up.
     *
     * @param forceReload build a fresh backend even when the requested model is the running one, which is
     *                    what "reload" means for a lane whose model file may have been replaced on disk
     */
    public synchronized Load acquire(TtsResolvedModel model, boolean forceReload) {
        if (!forceReload && backend != null && backend.isInitialized() && sameModel(model)) {
            return new Load(backend, Outcome.UNCHANGED);
        }
        TtsBackend previous = backend;
        TtsBackend candidate = createBackend(model.backendType());
        boolean initialized = candidate.initialize(model);
        if (!initialized) {
            // The candidate never becomes current: whatever was running keeps running, so a bad file or
            // a rejected switch cannot leave the lane silent.
            candidate.shutdown();
            if (previous == null || !previous.isInitialized()) {
                backend = null;
                loadedModel = null;
                return new Load(null, Outcome.FAILED);
            }
            return new Load(previous, Outcome.FAILED);
        }
        backend = candidate;
        loadedModel = model;
        if (previous != null) {
            previous.shutdown();
        }
        return new Load(candidate, Outcome.RELOADED);
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
            case MOSS -> new MossTtsBackend(env, codecExecution);
            case POCKET -> new com.rheinmetal.tianshu.function.echo.synthesis.pocket.PocketTtsBackend(env);
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
