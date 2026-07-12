package com.rheinmetal.tianshu.function.auxilium.module.system;

import java.util.List;

public record AXSystemPromptSet(
        String shortPrompt,
        String standardPrompt,
        String fullPrompt
) {
    public AXSystemPromptSet {
        shortPrompt = clean(shortPrompt);
        standardPrompt = clean(standardPrompt);
        fullPrompt = clean(fullPrompt);
        if (standardPrompt.isBlank()) {
            standardPrompt = !shortPrompt.isBlank() ? shortPrompt : fullPrompt;
        }
        if (shortPrompt.isBlank()) {
            shortPrompt = standardPrompt;
        }
        if (fullPrompt.isBlank()) {
            fullPrompt = standardPrompt;
        }
    }

    public static AXSystemPromptSet single(String prompt) {
        return new AXSystemPromptSet(prompt, prompt, prompt);
    }

    public List<String> largestFirst() {
        return List.of(fullPrompt, standardPrompt, shortPrompt);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
