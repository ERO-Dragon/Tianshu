package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;

import java.util.List;

/**
 * The synthesis port the lane talks to.
 *
 * <p>Loading a local model is not part of it: that belongs to {@link LocalTtsBackend}, so a backend without
 * model files (a cloud provider) is not asked to implement a signature it cannot honour. Everything here is
 * about speaking and being stopped.</p>
 */
public interface TtsBackend {
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
