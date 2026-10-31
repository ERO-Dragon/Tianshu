package com.rheinmetal.tianshu.function.echo.synthesis;

public enum TtsBackendType {
    SHERPA,
    MOSS,
    POCKET;

    public boolean autoregressive() {
        return this == MOSS;
    }
}
