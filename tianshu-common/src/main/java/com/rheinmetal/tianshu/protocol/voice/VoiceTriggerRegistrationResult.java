package com.rheinmetal.tianshu.protocol.voice;

import java.util.List;

public record VoiceTriggerRegistrationResult(String moduleId, boolean accepted, List<VoiceTriggerConflict> conflicts, String message) {
    public VoiceTriggerRegistrationResult {
        if (moduleId == null) moduleId = "";
        moduleId = moduleId.trim();
        conflicts = conflicts == null || conflicts.isEmpty()
                ? List.of()
                : conflicts.stream()
                .filter(VoiceTriggerConflict::valid)
                .toList();
        if (message == null) message = "";
    }

    public static VoiceTriggerRegistrationResult accepted(String moduleId, List<VoiceTriggerConflict> conflicts) {
        return new VoiceTriggerRegistrationResult(moduleId, true, conflicts, "");
    }
}
