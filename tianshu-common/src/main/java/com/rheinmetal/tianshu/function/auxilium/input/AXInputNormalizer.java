package com.rheinmetal.tianshu.function.auxilium.input;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;

import java.util.ArrayList;
import java.util.List;

public final class AXInputNormalizer {
    private static final int MAX_USER_TEXT_CHARS = 4000;
    private static final int MAX_PROVIDED_CONTEXT_CHARS = 8000;

    public AXNormalizedInput normalize(AXRequest request) {
        AXRequest effectiveRequest = request == null ? new AXRequest("AX.request", "", "") : request;
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
        return new AXNormalizedInput(effectiveRequest.requestKey(), userText, providedContext, effectiveRequest.source(), userText.isBlank(), truncated, tags);
    }

    private String normalizeWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("[\\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n");
    }
}
