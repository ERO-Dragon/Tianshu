package com.rheinmetal.tianshu.function.tts.synthesis;

public enum TtsBackendType {
    SHERPA,
    MOSS;

    public boolean autoregressive() {
        return this == MOSS;
    }
}
