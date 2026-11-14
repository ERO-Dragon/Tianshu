package com.rheinmetal.tianshu.protocol.runtime;

public enum ExecutionLane {
    MAIN,
    CPU,
    IO,
    AUDIO_IO,
    /** Fast, small audio synthesis work. */
    AUDIO_SYNTH_FAST,
    /** Long-running autoregressive audio synthesis. */
    AUDIO_SYNTH_AR,
    /** Continuous audio input streaming. */
    AUDIO_INPUT_STREAM,
    MODEL_LOAD,
    LONG,
    SCHEDULED
}
