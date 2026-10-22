package com.rheinmetal.tianshu.function.echo.synthesis;

public enum TtsBackendType {
    SHERPA,
    MOSS;

    public boolean autoregressive() {
        return this == MOSS;
    }
}
