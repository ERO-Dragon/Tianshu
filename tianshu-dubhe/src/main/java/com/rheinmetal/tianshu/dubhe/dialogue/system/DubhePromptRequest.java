package com.rheinmetal.tianshu.dubhe.dialogue.system;

public record DubhePromptRequest(
        DubhePromptTask task,
        DubhePromptLanguage language,
        String variant
) {
    public DubhePromptRequest {
        task = task == null ? DubhePromptTask.GENERAL_DUBHE : task;
        language = language == null ? DubhePromptLanguage.EN_US : language;
        variant = variant == null || variant.isBlank() ? "default" : variant.trim();
    }

    public static DubhePromptRequest general(DubhePromptLanguage language) {
        return new DubhePromptRequest(DubhePromptTask.GENERAL_DUBHE, language, "default");
    }
}
