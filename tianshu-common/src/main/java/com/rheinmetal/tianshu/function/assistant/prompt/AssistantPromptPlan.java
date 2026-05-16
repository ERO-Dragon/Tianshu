package com.rheinmetal.tianshu.function.assistant.prompt;

import java.util.List;

public record AssistantPromptPlan(
        AssistantPromptProfile profile,
        List<AssistantPromptSection> sections
) {
    public AssistantPromptPlan {
        profile = profile == null ? AssistantPromptProfile.defaultFor(AssistantPromptTask.GENERAL_ASSISTANT, AssistantPromptLanguage.ZH_CN) : profile;
        sections = sections == null ? List.of() : List.copyOf(sections);
    }
}
