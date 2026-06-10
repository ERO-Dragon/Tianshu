package com.rheinmetal.tianshu.function.ia.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record DialogueVoiceTriggerGroup(
        List<String> wakeWords,
        List<String> extraWords
) {
    public static final DialogueVoiceTriggerGroup EMPTY = new DialogueVoiceTriggerGroup(List.of(), List.of());

    public DialogueVoiceTriggerGroup {
        wakeWords = copyTextList(wakeWords);
        extraWords = copyTextList(extraWords);
    }

    public static DialogueVoiceTriggerGroup of(List<String> wakeWords, List<String> extraWords) {
        DialogueVoiceTriggerGroup group = new DialogueVoiceTriggerGroup(wakeWords, extraWords);
        return group.empty() ? EMPTY : group;
    }

    public boolean empty() {
        return wakeWords.isEmpty() && extraWords.isEmpty();
    }

    private static List<String> copyTextList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.trim());
        }
        return List.copyOf(normalized);
    }
}
