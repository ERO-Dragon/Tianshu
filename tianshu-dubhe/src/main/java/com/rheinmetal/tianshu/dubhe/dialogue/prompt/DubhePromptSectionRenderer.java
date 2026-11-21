package com.rheinmetal.tianshu.dubhe.dialogue.prompt;

import java.util.Map;

public final class DubhePromptSectionRenderer {
    private DubhePromptSectionRenderer() {
    }

    public static String render(DubhePromptBuildContext context, String key, Map<String, String> variables) {
        return context.texts().render(key, variables);
    }

    public static String renderContent(DubhePromptBuildContext context, String key, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return render(context, key, Map.of("content", content.trim()));
    }

    public static String renderLine(DubhePromptBuildContext context, String key, String variableName, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String effectiveVariableName = variableName == null || variableName.isBlank() ? "content" : variableName.trim();
        return render(context, key, Map.of(effectiveVariableName, value.trim()));
    }
}
