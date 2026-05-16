package com.rheinmetal.tianshu.function.tts.runtime;

public enum TtsFailureCode {
    RUNTIME_NOT_RUNNING,
    EMPTY_TEXT,
    SYNTHESIS_ENGINE_UNAVAILABLE,
    SYNTHESIS_FAILED,
    PLAYBACK_FAILED,
    REQUEST_NOT_FOUND,
    INVALID_REQUEST,
    CANCELLED,
    UNKNOWN
}
