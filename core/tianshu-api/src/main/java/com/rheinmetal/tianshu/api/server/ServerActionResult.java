package com.rheinmetal.tianshu.api.server;

/** The outcome of one server-side action request. */
public record ServerActionResult(Status status, String message) {

    public enum Status {
        /** Executed. */
        OK,
        /** The player is not allowed to run this action. */
        DENIED,
        /** The action is switched off in the server configuration. */
        DISABLED,
        /** The parameters were invalid; nothing was executed. */
        INVALID,
        /** The action was attempted and failed. */
        FAILED
    }

    public ServerActionResult {
        status = status == null ? Status.FAILED : status;
        message = message == null ? "" : message.trim();
    }

    public static ServerActionResult ok(String message) {
        return new ServerActionResult(Status.OK, message);
    }

    public static ServerActionResult of(Status status, String message) {
        return new ServerActionResult(status, message);
    }
}
