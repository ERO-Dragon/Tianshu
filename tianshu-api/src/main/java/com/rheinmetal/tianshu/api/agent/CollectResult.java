package com.rheinmetal.tianshu.api.agent;

/** What a bounded collect run achieved; {@code remaining} is how many blocks of the target id it still wanted. */
public record CollectResult(int collected, int remaining, String detail) {

    public CollectResult {
        if (collected < 0 || remaining < 0) {
            throw new IllegalArgumentException("collected and remaining must not be negative");
        }
        if (detail == null) {
            throw new IllegalArgumentException("detail is required");
        }
    }
}