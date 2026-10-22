package com.rheinmetal.tianshu.protocol.payload;

public enum PresenceActivityType {
    LOADING,
    PROCESSING_TASK,
    THINKING,
    RESPONDING,
    LISTENING,
    /** The module is retrying a failed start; presence shows it instead of the idle line. */
    RECONNECTING,
    /** The module failed for good; presence shows a readable error line. */
    ERROR,
    /** The module runs, but on a reduced backend; presence says so instead of staying silent. */
    DEGRADED
}
