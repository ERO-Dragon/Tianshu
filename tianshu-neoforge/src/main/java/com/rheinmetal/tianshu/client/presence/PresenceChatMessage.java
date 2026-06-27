package com.rheinmetal.tianshu.client.presence;

public record PresenceChatMessage(
        String senderName,
        String messageText,
        long receivedAtMillis
) {
    public PresenceChatMessage {
        senderName = clean(senderName);
        messageText = clean(messageText);
        if (receivedAtMillis <= 0L) {
            receivedAtMillis = System.currentTimeMillis();
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
