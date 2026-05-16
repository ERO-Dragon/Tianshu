package com.rheinmetal.tianshu.function.assistant.prompt;

import java.util.List;

public record AssistantPromptProfile(
        AssistantPromptTask task,
        AssistantPromptLanguage language,
        String identity,
        String behaviorRules,
        List<String> sectionOrder
) {
    public AssistantPromptProfile {
        task = task == null ? AssistantPromptTask.GENERAL_ASSISTANT : task;
        language = language == null ? AssistantPromptLanguage.ZH_CN : language;
        identity = identity == null ? "" : identity.trim();
        behaviorRules = behaviorRules == null ? "" : behaviorRules.trim();
        sectionOrder = sectionOrder == null ? List.of() : List.copyOf(sectionOrder);
    }

    public static AssistantPromptProfile defaultFor(AssistantPromptTask task, AssistantPromptLanguage language) {
        AssistantPromptLanguage effectiveLanguage = language == null ? AssistantPromptLanguage.ZH_CN : language;
        if (effectiveLanguage == AssistantPromptLanguage.EN_US) {
            return new AssistantPromptProfile(
                    task,
                    effectiveLanguage,
                    "You are the companion chat assistant of the Tianshu Minecraft mod.",
                    "Stay immersive, natural, concise, and reliable. Do not invent game state. When the user mentions game actions, only give advice and do not claim that you can directly execute them.",
                    List.of("identity", "rules", "persona", "scope", "long_term_memory", "world_summary", "provided_context")
            );
        }
        return new AssistantPromptProfile(
                task,
                effectiveLanguage,
                "你是天枢 Minecraft 模组中的随行聊天助手。",
                "保持沉浸感、自然、简洁、可靠；不要编造游戏状态；涉及游戏动作时只提供建议，不声称自己能直接执行。",
                List.of("identity", "rules", "persona", "scope", "long_term_memory", "world_summary", "provided_context")
        );
    }
}
