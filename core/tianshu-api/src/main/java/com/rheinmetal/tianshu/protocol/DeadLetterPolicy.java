package com.rheinmetal.tianshu.protocol;

public enum DeadLetterPolicy {
    LOG_ONLY,
    NOTIFY_SOURCE,
    RAISE_ERROR_ENVELOPE
}

