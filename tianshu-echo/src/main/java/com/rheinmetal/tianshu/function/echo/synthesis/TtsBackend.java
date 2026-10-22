package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;

import java.util.List;

public interface TtsBackend {
    boolean initialize(TtsResolvedModel model);

    default boolean preloadVoice(com.rheinmetal.tianshu.function.echo.runtime.TtsVoiceProfile voiceProfile) {
        return true;
    }

    boolean isInitialized();

    int sampleRate();

    default int contextualSentenceLimit(List<String> sentences) {
        return 1;
    }

    void synthesize(TtsRequest request, TtsAudioSink sink);

    void interrupt();

    void shutdown();
}
