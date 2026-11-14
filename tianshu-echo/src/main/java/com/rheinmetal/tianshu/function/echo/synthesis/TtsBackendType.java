package com.rheinmetal.tianshu.function.echo.synthesis;

public enum TtsBackendType {
    SHERPA,
    MOSS,
    POCKET,
    /** No model files: the provider answers with audio over the network. */
    CLOUD;

    public boolean autoregressive() {
        return this == MOSS || this == POCKET;
    }
}
