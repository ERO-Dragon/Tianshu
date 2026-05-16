package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsBackendType;

public record TtsBackendSnapshot(
        boolean resolved,
        boolean initialized,
        TtsBackendType backendType,
        String engineType,
        boolean autoregressive,
        int sampleRate,
        String modelDirectory,
        long updatedAtMillis
) {
    public TtsBackendSnapshot {
        engineType = engineType == null ? "" : engineType.trim();
        modelDirectory = modelDirectory == null ? "" : modelDirectory.trim();
        sampleRate = Math.max(0, sampleRate);
        updatedAtMillis = updatedAtMillis > 0L ? updatedAtMillis : System.currentTimeMillis();
    }

    public static TtsBackendSnapshot unavailable() {
        return new TtsBackendSnapshot(false, false, null, "", false, 0, "", System.currentTimeMillis());
    }
}
