package com.rheinmetal.tianshu.function.tts.runtime;

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
