package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record ChatAssistantIncomingChatPayload(String senderName, String messageText, String localPlayerName, String localLanguageCode, boolean mentionsSelf, long receivedAtMillis) implements ITianshuPayload {
    public ChatAssistantIncomingChatPayload {
        senderName = normalize(senderName, "System");
        messageText = normalize(messageText, "");
        localPlayerName = normalize(localPlayerName, "unknown");
        localLanguageCode = normalize(localLanguageCode, "zh_cn").toLowerCase();
        receivedAtMillis = receivedAtMillis > 0L ? receivedAtMillis : System.currentTimeMillis();
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}
