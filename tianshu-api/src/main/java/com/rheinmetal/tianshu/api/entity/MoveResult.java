package com.rheinmetal.tianshu.api.entity;

import java.util.Objects;

/** Where a move ended. {@code position} is the body's position after the attempt, even when it did not arrive. */
public record MoveResult(ActionOutcome outcome, ActorPosition position, String detail) {

    public MoveResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(detail, "detail");
    }
}