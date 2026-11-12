package com.rheinmetal.tianshu.api.tool;

import java.util.Objects;

/** A typed failure of a tool call; {@link #code()} is the stable, wire-level reason. */
public final class ToolException extends RuntimeException {

    private final ToolError code;

    public ToolException(ToolError code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ToolException(ToolError code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ToolError code() {
        return code;
    }
}