package com.rheinmetal.tianshu.function.assistant.input;

import java.util.List;

public record AssistantNormalizedInput(
        String requestKey,
        String userText,
        String providedContext,
        AssistantInputSource source,
        boolean empty,
        boolean truncated,
        List<String> tags
) {
    public AssistantNormalizedInput {
        requestKey = requestKey == null || requestKey.isBlank() ? "assistant.request" : requestKey.trim();
        userText = userText == null ? "" : userText.trim();
        providedContext = providedContext == null ? "" : providedContext.trim();
        source = source == null ? AssistantInputSource.UNKNOWN : source;
        empty = userText.isBlank();
        tags = tags == null ? List.of() : tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
