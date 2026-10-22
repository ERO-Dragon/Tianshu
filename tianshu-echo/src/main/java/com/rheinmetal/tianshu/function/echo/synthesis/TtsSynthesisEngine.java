package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.function.echo.runtime.TtsBackendSnapshot;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;

import java.util.List;

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

    default void clearModel() {
        shutdown();
    }

    void synthesize(TtsRequest request, TtsAudioSink sink);

    void interrupt();

    void shutdown();
}
