package com.rheinmetal.tianshu.api.tool;

/** Why a tool call did not produce a result. The codes are part of the wire contract with the agent. */
public enum ToolError {

    /** No tool with that id is registered on this side. */
    UNKNOWN_TOOL,

    /** The tool exists but is switched off. */
    DISABLED,

    /** The caller does not hold the permission the tool requires. */
    NO_PERMISSION,

    /** A server-side tool was called where no server component is present. */
    UNSUPPORTED_SIDE,

    /**
     * The acting agent body does not declare the capability the tool needs. This is a refusal, distinct from
     * {@link #FAILED}: a floating body with no hands is not broken, it simply cannot mine.
     */
    UNSUPPORTED_ACTOR,

    /** The capability is not installed on this side: the tool exists but its data source is absent. */
    UNAVAILABLE,

    /** The argument object could not be deserialized. */
    INVALID_ARGS,

    /** The tool did not finish inside its timeout. */
    TIMEOUT,
    RATE_LIMITED,
    CANCELLED,

    /** The caller already has too many calls in flight; the request was not started. */
    BUSY,

    /** The tool itself failed. */
    FAILED;

    /**
     * Reads a wire code, falling back to {@code fallback} for anything this enum does not name.
     *
     * <p>The envelope's {@code code} is written by whoever produced the error: the capability table emits
     * {@code UNKNOWN_CAPABILITY}, which is deliberately not a tool code, and a third-party provider may emit its
     * own. A reader that assumes otherwise throws while it is already handling a failure, which loses the code
     * and the message together.</p>
     */
    public static ToolError fromWire(String code, ToolError fallback) {
        if (code == null || code.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(code.trim());
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}