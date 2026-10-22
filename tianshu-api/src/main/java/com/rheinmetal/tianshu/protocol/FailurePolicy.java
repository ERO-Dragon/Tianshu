package com.rheinmetal.tianshu.protocol;

public enum FailurePolicy {
    PROPAGATE_CANCEL,
    FALLBACK,
    IGNORE,
    RETRY,
    REPORT_ONLY
}

