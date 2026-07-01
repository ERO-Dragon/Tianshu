package com.rheinmetal.tianshu.function.auxilium.core.prompt;

import java.util.Map;
import java.util.Objects;

public final class AXPromptSectionRenderer {
    private AXPromptSectionRenderer() {
    }

    public static String render(AXPromptBuildContext context, String key, Map<String, String> variables) {
        return context.texts().render(key, variables);
    }

    public static String renderContent(AXPromptBuildContext context, String key, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return render(context, key, Map.of("content", content.trim()));
    }

    public static String renderLine(AXPromptBuildContext context, String key, String variableName, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String effectiveVariableName = variableName == null || variableName.isBlank() ? "content" : variableName.trim();
        return render(context, key, Map.of(effectiveVariableName, value.trim()));
    }

    public static String joinLines(Iterable<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : Objects.requireNonNullElse(lines, java.util.List.<String>of())) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(line.trim());
        }
        return builder.toString();
    }
}
