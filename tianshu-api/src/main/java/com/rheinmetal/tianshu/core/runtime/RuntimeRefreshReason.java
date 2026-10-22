package com.rheinmetal.tianshu.core.runtime;

public enum RuntimeRefreshReason {
    MANUAL,
    ENVIRONMENT_READY,
    RESOURCE_CHANGED,
    RESTART_REQUESTED
}
