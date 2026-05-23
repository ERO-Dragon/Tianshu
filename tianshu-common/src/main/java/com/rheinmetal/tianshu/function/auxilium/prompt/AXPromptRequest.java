package com.rheinmetal.tianshu.function.auxilium.prompt;

public record AXPromptRequest(
        AXPromptTask task,
        AXPromptLanguage language,
        String variant
) {
    public AXPromptRequest {
        task = task == null ? AXPromptTask.GENERAL_AX : task;
        language = language == null ? AXPromptLanguage.EN_US : language;
        variant = variant == null || variant.isBlank() ? "default" : variant.trim();
    }

    public static AXPromptRequest general(AXPromptLanguage language) {
        return new AXPromptRequest(AXPromptTask.GENERAL_AX, language, "default");
    }
}
