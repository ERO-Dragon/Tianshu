package com.rheinmetal.tianshu.function.echo.synthesis.moss;

enum MossFrameGenerationTermination {
    NATURAL_END,
    /** The model stopped saying anything but kept emitting frames; the utterance ends here, truncated. */
    DEGENERATE_END,
    CANCELLED,
    FRAME_LIMIT_REACHED
}
