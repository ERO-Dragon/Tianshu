package com.rheinmetal.tianshu.protocol.trigger;

import java.util.List;

public record TriggerRegistrationResult(String moduleId, boolean accepted, List<TriggerConflict> conflicts, String message) {
    public TriggerRegistrationResult {
        if (moduleId == null) moduleId = "";
        moduleId = moduleId.trim();
        conflicts = conflicts == null || conflicts.isEmpty()
                ? List.of()
                : conflicts.stream()
                .filter(TriggerConflict::valid)
                .toList();
        if (message == null) message = "";
    }

    public static TriggerRegistrationResult accepted(String moduleId, List<TriggerConflict> conflicts) {
        return new TriggerRegistrationResult(moduleId, true, conflicts, "");
    }
}
