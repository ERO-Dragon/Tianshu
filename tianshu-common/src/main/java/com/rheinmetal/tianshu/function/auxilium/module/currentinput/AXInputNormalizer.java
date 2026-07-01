package com.rheinmetal.tianshu.function.auxilium.module.currentinput;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;

import java.util.ArrayList;
import java.util.List;

public final class AXInputNormalizer {
    private static final int MAX_USER_TEXT_CHARS = 4000;
    private static final int MAX_DELIVERY_SNAPSHOT_CHARS = 8000;

    public AXNormalizedInput normalize(AXRequest request) {
        AXRequest effectiveRequest = request == null ? new AXRequest("AX.request", "", "") : request;
        List<String> tags = new ArrayList<>();
        String userText = normalizeWhitespace(effectiveRequest.userText());
        String deliverySnapshot = normalizeWhitespace(effectiveRequest.deliverySnapshot());
        boolean truncated = false;
        if (userText.length() > MAX_USER_TEXT_CHARS) {
            userText = userText.substring(0, MAX_USER_TEXT_CHARS);
            truncated = true;
            tags.add("user_text_truncated");
        }
        if (deliverySnapshot.length() > MAX_DELIVERY_SNAPSHOT_CHARS) {
            deliverySnapshot = deliverySnapshot.substring(0, MAX_DELIVERY_SNAPSHOT_CHARS);
            truncated = true;
            tags.add("delivery_snapshot_truncated");
        }
        return new AXNormalizedInput(effectiveRequest.requestKey(), userText, deliverySnapshot, effectiveRequest.source(), userText.isBlank(), truncated, tags);
    }

    private String normalizeWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("[\\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n");
    }
}
