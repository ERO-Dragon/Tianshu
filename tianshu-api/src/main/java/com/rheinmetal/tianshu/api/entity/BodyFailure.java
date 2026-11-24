package com.rheinmetal.tianshu.api.entity;

import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.Optional;

/** Stable NPC failure identifiers shared by tool replies and localized player-facing views. */
public enum BodyFailure {
    CONTROLLER_REQUIRED("body.controller.required", ToolError.NO_PERMISSION),
    CONTROLLER_MISMATCH("body.controller.mismatch", ToolError.NO_PERMISSION),
    CONTROLLER_UNVERIFIED("body.controller.unverified", ToolError.NO_PERMISSION),
    SELF_ONLY("body.controller.self_only", ToolError.NO_PERMISSION),
    PLAYER_DENIED("body.access.denied", ToolError.NO_PERMISSION),
    KIND_INVALID("body.kind.invalid", ToolError.INVALID_ARGS),
    KIND_FOREIGN("body.kind.foreign", ToolError.NO_PERMISSION),
    DIMENSION_UNAVAILABLE("body.dimension.unavailable", ToolError.UNAVAILABLE),
    SPAWN_FAILED("body.spawn.failed", ToolError.FAILED),
    PLAYER_UNAVAILABLE("body.player.unavailable", ToolError.UNAVAILABLE),
    BODY_REQUIRED("body.target.required", ToolError.INVALID_ARGS),
    WRONG_TARGET("body.target.mismatch", ToolError.INVALID_ARGS);

    private final String id;
    private final ToolError code;

    BodyFailure(String id, ToolError code) {
        this.id = id;
        this.code = code;
    }

    public ToolException exception() {
        return new ToolException(code, id);
    }

    public String translationKey() {
        return "tianshu." + id;
    }

    public static Optional<BodyFailure> fromId(String id) {
        for (BodyFailure reason : values()) {
            if (reason.id.equals(id)) return Optional.of(reason);
        }
        return Optional.empty();
    }
}
