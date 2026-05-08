package com.rheinmetal.tianshu.protocol.runtime;

public enum ExecutionLane {
    MAIN,
    CPU,
    IO,
    AUDIO_IO,
    TTS_FAST,
    TTS_AUTOREGRESSIVE,
    ASR_STREAM,
    MODEL_LOAD,
    LONG,
    SCHEDULED
}
