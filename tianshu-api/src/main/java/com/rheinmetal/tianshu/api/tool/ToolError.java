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

    /** The capability is not installed on this side: the tool exists but its data source is absent. */
    UNAVAILABLE,

    /** The argument object could not be deserialized. */
    INVALID_ARGS,

    /** The tool did not finish inside its timeout. */
    TIMEOUT,
    RATE_LIMITED,

    /** The tool itself failed. */
    FAILED
}