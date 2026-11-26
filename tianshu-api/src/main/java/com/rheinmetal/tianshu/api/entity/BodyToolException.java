package com.rheinmetal.tianshu.api.entity;

import com.rheinmetal.tianshu.api.tool.ToolException;

/** A rejected NPC operation with a stable reason and a diagnostic message. */
public final class BodyToolException extends ToolException {
    private final BodyFailure reason;

    BodyToolException(BodyFailure reason, String message) {
        super(reason.code(), message);
        this.reason = reason;
    }

    public BodyFailure reason() {
        return reason;
    }

    @Override
    public String wireMessage() {
        return reason.id();
    }
}
