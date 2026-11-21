package com.rheinmetal.tianshu.echo.runtime;

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
