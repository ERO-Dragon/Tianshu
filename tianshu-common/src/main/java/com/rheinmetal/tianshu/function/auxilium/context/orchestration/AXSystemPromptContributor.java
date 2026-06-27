package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;

public final class AXSystemPromptContributor implements AXPromptContributor {
    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        StringBuilder text = new StringBuilder();
        appendSection(text, title(context, "身份", "Identity"), context.profile().identity());
        appendSection(text, title(context, "行为规则", "Behavior Rules"), context.profile().behaviorRules());
        appendSection(text, title(context, "提示词分区约束", "Prompt Section Rules"), sectionRules(context.language()));
        builder.addSystemMessage(text.toString());
    }

    private String title(AXPromptBuildContext context, String zhCn, String enUs) {
        return context.language() == AXPromptLanguage.EN_US ? enUs : zhCn;
    }

    private String sectionRules(AXPromptLanguage language) {
        if (language == AXPromptLanguage.EN_US) {
            return "Treat game context, game knowledge, player memory, recent dialogue, and current input as separate sources. Do not claim that AX can execute game actions unless a tool result explicitly says so.";
        }
        return "将动态环境、静态知识、玩家记忆、近期对话和当前输入视为不同来源；除非工具结果明确说明，否则不要声称 AX 能直接执行游戏动作。";
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append("<").append(title).append(">\n").append(content.trim()).append("\n</").append(title).append(">");
    }
}
