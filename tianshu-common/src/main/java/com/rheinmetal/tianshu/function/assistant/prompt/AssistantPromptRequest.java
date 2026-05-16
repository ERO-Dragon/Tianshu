package com.rheinmetal.tianshu.function.assistant.prompt;

public record AssistantPromptRequest(
        AssistantPromptTask task,
        AssistantPromptLanguage language,
        String variant
) {
    public AssistantPromptRequest {
        task = task == null ? AssistantPromptTask.GENERAL_ASSISTANT : task;
        language = language == null ? AssistantPromptLanguage.ZH_CN : language;
        variant = variant == null || variant.isBlank() ? "default" : variant.trim();
    }

    public static AssistantPromptRequest general(AssistantPromptLanguage language) {
        return new AssistantPromptRequest(AssistantPromptTask.GENERAL_ASSISTANT, language, "default");
    }
}
