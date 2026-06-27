package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record PresenceChatMessagePayload(
        String senderId,
        String senderName,
        String messageText
) implements ITianshuPayload {
    public static final String TOPIC = "PRESENCE.CHAT_MESSAGE";

    public PresenceChatMessagePayload {
        senderId = clean(senderId, "");
        senderName = clean(senderName, "");
        messageText = clean(messageText, "");
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
