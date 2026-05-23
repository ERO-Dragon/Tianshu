package com.rheinmetal.tianshu.function.auxilium.prompt;

public record AXPromptResourceKey(
        AXPromptTask task,
        AXPromptLanguage language,
        String variant
) {
    public AXPromptResourceKey {
        task = task == null ? AXPromptTask.GENERAL_AX : task;
        language = language == null ? AXPromptLanguage.EN_US : language;
        variant = variant == null || variant.isBlank() ? "default" : variant.trim();
    }

    public String fileName() {
        return task.name().toLowerCase() + "." + language.code() + "." + safe(variant) + ".json";
    }

    private String safe(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
