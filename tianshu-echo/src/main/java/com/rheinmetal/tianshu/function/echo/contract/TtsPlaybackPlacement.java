package com.rheinmetal.tianshu.function.echo.contract;

public enum TtsPlaybackPlacement {
    DROP_IF_BUSY,
    QUEUE_AFTER_SESSION,
    INSERT_AFTER_SESSION,
    INSERT_AFTER_SENTENCE,
    CANCEL_SENTENCE_AND_PLAY,
    CANCEL_SESSION_AND_PLAY
}
