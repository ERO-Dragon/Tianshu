package com.rheinmetal.tianshu.snapshot;

public final class ChatMessageData {

    public final String senderName;
    public final String messageText;

    public ChatMessageData(
            String senderName,
            String messageText
    ) {
        this.senderName = senderName;
        this.messageText = messageText;
    }

    public String getSenderName() { return senderName; }
    public String getMessageText() { return messageText; }
}
