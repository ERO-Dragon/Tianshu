package com.rheinmetal.tianshu.function.assistant.input;

import com.rheinmetal.tianshu.function.assistant.AssistantRequest;

import java.util.ArrayList;
import java.util.List;

public final class AssistantInputNormalizer {
    private static final int MAX_USER_TEXT_CHARS = 4000;
    private static final int MAX_PROVIDED_CONTEXT_CHARS = 8000;

    public AssistantNormalizedInput normalize(AssistantRequest request) {
        AssistantRequest effectiveRequest = request == null ? new AssistantRequest("assistant.request", "", "") : request;
        List<String> tags = new ArrayList<>();
        String userText = normalizeWhitespace(effectiveRequest.userText());
        String providedContext = normalizeWhitespace(effectiveRequest.providedContext());
        boolean truncated = false;
        if (userText.length() > MAX_USER_TEXT_CHARS) {
            userText = userText.substring(0, MAX_USER_TEXT_CHARS);
            truncated = true;
            tags.add("user_text_truncated");
        }
        if (providedContext.length() > MAX_PROVIDED_CONTEXT_CHARS) {
            providedContext = providedContext.substring(0, MAX_PROVIDED_CONTEXT_CHARS);
            truncated = true;
            tags.add("provided_context_truncated");
        }
        AssistantInputSource source = inferSource(effectiveRequest.requestKey());
        return new AssistantNormalizedInput(effectiveRequest.requestKey(), userText, providedContext, source, userText.isBlank(), truncated, tags);
    }

    private AssistantInputSource inferSource(String requestKey) {
        String key = requestKey == null ? "" : requestKey.toLowerCase();
        if (key.contains("voice")) {
            return AssistantInputSource.VOICE;
        }
        if (key.contains("ui")) {
            return AssistantInputSource.UI;
        }
        if (key.contains("forward")) {
            return AssistantInputSource.FORWARDED;
        }
        if (key.contains("chat")) {
            return AssistantInputSource.CHAT;
        }
        return AssistantInputSource.UNKNOWN;
    }

    private String normalizeWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("[\\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n");
    }
}
