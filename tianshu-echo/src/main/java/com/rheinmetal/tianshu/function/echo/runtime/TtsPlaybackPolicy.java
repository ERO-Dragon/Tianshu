package com.rheinmetal.tianshu.function.echo.runtime;

public enum TtsPlaybackPolicy {
    QUEUE,
    INSERT_AFTER_SESSION,
    INSERT_AFTER_SENTENCE,
    CANCEL_SENTENCE_AND_PLAY,
    CANCEL_SESSION_AND_PLAY,
    REPLACE_CURRENT,
    DROP_IF_BUSY
}
