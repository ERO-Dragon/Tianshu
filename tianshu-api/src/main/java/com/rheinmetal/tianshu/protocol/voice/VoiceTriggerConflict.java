package com.rheinmetal.tianshu.protocol.voice;

import java.util.List;

public record VoiceTriggerConflict(String word, List<String> moduleIds) {
    public VoiceTriggerConflict {
        if (word == null) word = "";
        word = word.trim();
        moduleIds = moduleIds == null || moduleIds.isEmpty()
                ? List.of()
                : moduleIds.stream()
                .filter(moduleId -> moduleId != null && !moduleId.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public boolean valid() {
        return !word.isBlank() && moduleIds.size() > 1;
    }
}
