package com.rheinmetal.tianshu.api.capability;

import java.util.Objects;

/**
 * What actually happened in one workflow run.
 *
 * <p>Carried instead of a bare envelope so a failure can say <em>where</em> it happened: "workflow X (provided by Y)
 * failed at step 2 (modA:aim_at_block): NO_PERMISSION" is actionable, "the workflow failed" is not — and the difference
 * matters most exactly when a composite is shared between modules.</p>
 */
public record WorkflowRun(
        CapabilityId workflow,
        int executedSteps,
        int totalSteps,
        boolean completed,
        boolean cancelled,
        int failedStep,
        String failure
) {

    public WorkflowRun {
        Objects.requireNonNull(workflow, "workflow");
        failure = failure == null ? "" : failure;
    }

    static WorkflowRun finished(CapabilityId workflow, int total) {
        return new WorkflowRun(workflow, total, total, true, false, -1, "");
    }

    static WorkflowRun failedAt(CapabilityId workflow, int executed, int total, int step, String failure) {
        return new WorkflowRun(workflow, executed, total, false, false, step, failure);
    }

    static WorkflowRun stoppedAt(CapabilityId workflow, int executed, int total) {
        return new WorkflowRun(workflow, executed, total, false, true, -1, "cancelled after step " + executed);
    }

    /** True when the run needs nobody's attention: either finished, or cancelled on purpose. */
    public boolean ok() {
        return completed || cancelled;
    }
}