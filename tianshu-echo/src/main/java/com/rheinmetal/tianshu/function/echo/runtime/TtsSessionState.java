package com.rheinmetal.tianshu.function.echo.runtime;

public enum TtsSessionState {
    CREATED,
    QUEUED,
    SYNTHESIZING,
    PLAYING,
    DRAINING,
    COMPLETED,
    CANCELLED,
    FAILED
}
