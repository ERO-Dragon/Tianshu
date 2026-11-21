package com.rheinmetal.tianshu.dubhe.module.currentinput;

import java.util.List;

public record DubheNormalizedInput(
        String requestKey,
        String userText,
        String deliverySnapshot,
        DubheInputSource source,
        boolean empty,
        boolean truncated,
        List<String> tags
) {
    public DubheNormalizedInput {
        requestKey = requestKey == null || requestKey.isBlank() ? "DUBHE.request" : requestKey.trim();
        userText = userText == null ? "" : userText.trim();
        deliverySnapshot = deliverySnapshot == null ? "" : deliverySnapshot.trim();
        source = source == null ? DubheInputSource.UNKNOWN : source;
        empty = userText.isBlank();
        tags = tags == null ? List.of() : tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
