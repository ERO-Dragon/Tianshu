package com.rheinmetal.tianshu.function.auxilium.prompt;

import java.util.List;

public record AXPromptProfile(
        AXPromptTask task,
        AXPromptLanguage language,
        String identity,
        String behaviorRules,
        List<String> sectionOrder
) {
    public AXPromptProfile {
        task = task == null ? AXPromptTask.GENERAL_AX : task;
        language = language == null ? AXPromptLanguage.EN_US : language;
        identity = identity == null ? "" : identity.trim();
        behaviorRules = behaviorRules == null ? "" : behaviorRules.trim();
        sectionOrder = sectionOrder == null ? List.of() : List.copyOf(sectionOrder);
    }

    public static AXPromptProfile defaultFor(AXPromptTask task, AXPromptLanguage language) {
        AXPromptLanguage effectiveLanguage = language == null ? AXPromptLanguage.EN_US : language;
        if (effectiveLanguage == AXPromptLanguage.EN_US) {
            return new AXPromptProfile(
                    task,
                    effectiveLanguage,
                    "You are the companion chat AX of the Tianshu Minecraft mod.",
                    "Stay immersive, natural, concise, and reliable. Do not invent game state. When the user mentions game actions, only give advice and do not claim that you can directly execute them.",
                    List.of("identity", "rules", "persona", "scope", "long_term_memory", "world_summary", "provided_context")
            );
        }
        return new AXPromptProfile(
                task,
                effectiveLanguage,
                "你是天枢 Minecraft 模组中的随行聊天助手。",
                "保持沉浸感、自然、简洁、可靠；不要编造游戏状态；涉及游戏动作时只提供建议，不声称自己能直接执行。",
                List.of("identity", "rules", "persona", "scope", "long_term_memory", "world_summary", "provided_context")
        );
    }
}
