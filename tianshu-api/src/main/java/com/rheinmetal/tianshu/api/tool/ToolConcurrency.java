package com.rheinmetal.tianshu.api.tool;

/** The one place the platform's tool-call concurrency bounds live. */
public final class ToolConcurrency {

    /** Total unknown calls one client may wait on at once. */
    public static final int MAX_PENDING = 64;
    /** One caller cannot occupy the whole window; both client and server enforce this bound. */
    public static final int MAX_IN_FLIGHT_PER_CALLER = 8;

    private ToolConcurrency() {
    }
}
