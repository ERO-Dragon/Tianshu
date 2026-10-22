package com.rheinmetal.tianshu.function.auxilium.module.currentinput;

import java.util.List;

public record AXNormalizedInput(
        String requestKey,
        String userText,
        String deliverySnapshot,
        AXInputSource source,
        boolean empty,
        boolean truncated,
        List<String> tags
) {
    public AXNormalizedInput {
        requestKey = requestKey == null || requestKey.isBlank() ? "AX.request" : requestKey.trim();
        userText = userText == null ? "" : userText.trim();
        deliverySnapshot = deliverySnapshot == null ? "" : deliverySnapshot.trim();
        source = source == null ? AXInputSource.UNKNOWN : source;
        empty = userText.isBlank();
        tags = tags == null ? List.of() : tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
