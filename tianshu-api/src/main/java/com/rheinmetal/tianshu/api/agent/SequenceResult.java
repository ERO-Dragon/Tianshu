package com.rheinmetal.tianshu.api.agent;

import java.util.List;

/**
 * What a bounded sequence achieved: one report per attempted step and the index of the step that stopped it.
 *
 * <p>{@code failedIndex = -1} means every step succeeded. A stop is reported, not thrown: the caller sees exactly
 * how far the plan got and why the next step did not run.</p>
 */
public record SequenceResult(List<StepReport> steps, int failedIndex, String detail) {

    public SequenceResult {
        if (steps == null) {
            throw new IllegalArgumentException("steps is required");
        }
        if (failedIndex < -1) {
            throw new IllegalArgumentException("failedIndex must be -1 or a step index");
        }
        if (detail == null) {
            throw new IllegalArgumentException("detail is required");
        }
    }

    /** One attempted step; {@code ok = false} means this step is the one that stopped the sequence. */
    public record StepReport(String kind, boolean ok, String detail) {

        public StepReport {
            if (kind == null) {
                throw new IllegalArgumentException("kind is required");
            }
            if (detail == null) {
                throw new IllegalArgumentException("detail is required");
            }
        }
    }
}