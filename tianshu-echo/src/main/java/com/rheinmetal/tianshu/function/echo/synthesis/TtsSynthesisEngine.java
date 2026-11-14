package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.function.echo.runtime.TtsBackendSnapshot;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;

import java.util.List;
import java.util.Optional;

public interface TtsSynthesisEngine {
    boolean initialize();

    default boolean preloadVoice(com.rheinmetal.tianshu.function.echo.runtime.TtsVoiceProfile voiceProfile) {
        return true;
    }

    boolean isInitialized();

    boolean isAutoregressive();

    int sampleRate();

    TtsBackendSnapshot backendSnapshot();

    default int contextualSentenceLimit(List<String> sentences) {
        return 1;
    }

    boolean useModel(String modelName);

    /**
     * Switches to an explicit selection.
     *
     * <p>The coordinator carries a selection through a switch or a preview; resolving the name again inside
     * the engine would let the two disagree mid-flight.</p>
     */
    default boolean useSelection(TtsBackendSelection selection) {
        // Implementations that only know model names keep working for local selections; the engine this
        // build ships overrides this so a cloud selection is not silently dropped.
        if (selection instanceof LocalTtsBackendSelection local) {
            return useModel(local.label());
        }
        return false;
    }

    /**
     * Resolves what a configured model name denotes, or empty when nothing resolvable matches.
     *
     * <p>Model names are a settings-level concept; the load path speaks selections, because a backend is not
     * always a local model. This is the one conversion point, so callers that only hold a name (the preview
     * path, the "running model" row) do not each re-implement the resolution rules.</p>
     */
    default Optional<TtsBackendSelection> selectionFor(String modelName) {
        return Optional.empty();
    }

    /**
     * The model the running backend actually speaks with; empty when the engine cannot tell.
     *
     * <p>Surfaced so a caller can compare it with the configured model: a failed preview restore leaves the
     * engine on the preview model while the configuration still names the old one, and that divergence has
     * to be visible instead of being guessed from a failure bit.</p>
     */
    default String activeModelName() {
        return "";
    }

    /**
     * Rebuilds the running backend for the current selection.
     *
     * <p>The default is the historical stop-then-start pair. The engine this build ships overrides it to
     * load the replacement before it closes what is running, because a reload that drops the old backend
     * first leaves the lane unable to speak for the length of the load — and unable to speak at all when
     * the replacement turns out to be broken.</p>
     */
    default boolean reload() {
        shutdown();
        return initialize();
    }

    default void clearModel() {
        shutdown();
    }

    void synthesize(TtsRequest request, TtsAudioSink sink);

    void interrupt();

    void shutdown();
}
