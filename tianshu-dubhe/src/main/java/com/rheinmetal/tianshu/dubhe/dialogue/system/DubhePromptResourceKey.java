package com.rheinmetal.tianshu.dubhe.dialogue.system;

public record DubhePromptResourceKey(
        DubhePromptTask task,
        DubhePromptLanguage language,
        String variant
) {
    public DubhePromptResourceKey {
        task = task == null ? DubhePromptTask.GENERAL_DUBHE : task;
        language = language == null ? DubhePromptLanguage.EN_US : language;
        variant = variant == null || variant.isBlank() ? "default" : variant.trim();
    }

    public String fileName() {
        return task.name().toLowerCase() + "." + language.code() + "." + safe(variant) + ".json";
    }

    private String safe(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
