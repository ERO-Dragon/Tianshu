package com.rheinmetal.tianshu.function.tts.synthesis;

import com.rheinmetal.tianshu.function.tts.runtime.TtsRequest;

public interface TtsBackend {
    boolean initialize(TtsResolvedModel model);

    boolean isInitialized();

    int sampleRate();

    void synthesize(TtsRequest request, TtsAudioSink sink);

    void interrupt();

    void shutdown();
}
