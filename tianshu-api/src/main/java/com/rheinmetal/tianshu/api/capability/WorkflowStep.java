package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * One step of a published workflow: which capability to call, and with what arguments.
 *
 * <p>The step names a capability rather than a piece of code, and that is the whole point: the executor resolves it
 * through the capability table at run time, so the step runs in whichever module provides it, under the caller's
 * permissions. A provider that wrote its workflow as steps published data, not a way to borrow its authority.</p>
 */
public record WorkflowStep(CapabilityId capability, JsonObject args) {

    public WorkflowStep {
        Objects.requireNonNull(capability, "capability");
        args = args == null ? new JsonObject() : args.deepCopy();
    }

    public static WorkflowStep of(CapabilityId capability) {
        return new WorkflowStep(capability, new JsonObject());
    }

    public static WorkflowStep of(String capability) {
        return of(CapabilityId.parse(capability));
    }
}