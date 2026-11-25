package com.rheinmetal.tianshu.api.capability;

import java.util.List;
import java.util.Objects;

/**
 * A workflow a provider publishes as data: its steps and what running it costs.
 *
 * <p>Two things are required of every composite capability, published or not: <strong>what it will cost</strong> (time
 * and whether it touches the world), so a model and an operator can judge it before running it. The step list is the
 * extra that a provider <em>may</em> publish: when it does, the base's executor can count the steps, refuse a plan longer
 * than its own budget, and stop between steps. When it does not, the capability is an ordinary handler and only
 * cancellation and the call timeout apply.</p>
 *
 * <p>Steps execute under the <strong>caller's</strong> permissions, never the provider's, and the whole run is checked
 * before the first step: a plan the caller could not finish is refused instead of half-executed.</p>
 */
public record WorkflowDeclaration(
        CapabilityId id,
        String description,
        List<WorkflowStep> steps,
        int expectedSeconds,
        boolean changesWorld
) {

    public WorkflowDeclaration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(description, "description");
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (description.isBlank()) {
            throw new IllegalArgumentException("a workflow needs a description: it is what the model reads");
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("workflow " + id + " declares no steps");
        }
        if (expectedSeconds < 0) {
            throw new IllegalArgumentException("workflow " + id + " declares a negative cost");
        }
    }

    /** How many steps the plan actually has — the number the executor counts against. */
    public int stepCount() {
        return steps.size();
    }
}