package com.rheinmetal.tianshu.core.lifecycle.status;

import com.rheinmetal.tianshu.core.lifecycle.ModuleLifecyclePhase;
import com.rheinmetal.tianshu.core.lifecycle.installation.ModuleFailurePolicy;

public record ModuleLifecycleStatus(
        String moduleId,
        ModuleFailurePolicy failurePolicy,
        ModuleLifecycleState state,
        ModuleLifecyclePhase lastPhase,
        String failureReason
) {
    public boolean failed() {
        return state == ModuleLifecycleState.FAILED;
    }

    public boolean active() {
        return state == ModuleLifecycleState.REGISTERED
                || state == ModuleLifecycleState.PREPARED
                || state == ModuleLifecycleState.STARTED;
    }
}
