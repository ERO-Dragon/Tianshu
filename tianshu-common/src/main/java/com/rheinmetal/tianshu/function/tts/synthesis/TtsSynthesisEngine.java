package com.rheinmetal.tianshu.function.tts.synthesis;

import com.rheinmetal.tianshu.function.tts.runtime.TtsBackendSnapshot;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequest;

public interface TtsSynthesisEngine {
    boolean initialize();

    boolean isInitialized();

    boolean isAutoregressive();

    int sampleRate();

    TtsBackendSnapshot backendSnapshot();

    boolean useModel(String modelName);

    void synthesize(TtsRequest request, TtsAudioSink sink);

    void interrupt();

    void shutdown();
}
