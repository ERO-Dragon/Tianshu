package com.rheinmetal.tianshu.api.agent;

/** How a follow run ended and how far the target was at the end. */
public record FollowResult(ActionOutcome outcome, double distance, String detail) {

    public FollowResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
        if (detail == null) {
            throw new IllegalArgumentException("detail is required");
        }
    }
}