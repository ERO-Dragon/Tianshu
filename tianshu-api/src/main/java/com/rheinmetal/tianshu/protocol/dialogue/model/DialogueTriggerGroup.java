package com.rheinmetal.tianshu.protocol.dialogue.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record DialogueTriggerGroup(
        List<String> wakeWords,
        List<String> extraWords
) {
    public static final DialogueTriggerGroup EMPTY = new DialogueTriggerGroup(List.of(), List.of());

    public DialogueTriggerGroup {
        wakeWords = copyTextList(wakeWords);
        extraWords = copyTextList(extraWords);
    }

    public static DialogueTriggerGroup of(List<String> wakeWords, List<String> extraWords) {
        DialogueTriggerGroup group = new DialogueTriggerGroup(wakeWords, extraWords);
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
