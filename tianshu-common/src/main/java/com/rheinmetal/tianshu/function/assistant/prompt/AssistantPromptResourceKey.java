package com.rheinmetal.tianshu.function.assistant.prompt;

public record AssistantPromptResourceKey(
        AssistantPromptTask task,
        AssistantPromptLanguage language,
        String variant
) {
    public AssistantPromptResourceKey {
        task = task == null ? AssistantPromptTask.GENERAL_ASSISTANT : task;
        language = language == null ? AssistantPromptLanguage.ZH_CN : language;
        variant = variant == null || variant.isBlank() ? "default" : variant.trim();
    }

    public String fileName() {
        return task.name().toLowerCase() + "." + language.code() + "." + safe(variant) + ".json";
    }

    private String safe(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
