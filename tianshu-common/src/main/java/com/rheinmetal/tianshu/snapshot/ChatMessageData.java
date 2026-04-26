package com.rheinmetal.tianshu.snapshot;

public final class ChatMessageData {

    public final String senderName;
    public final String messageText;
    public final long timestamp;
    public final boolean mentionsSelf;

    public ChatMessageData(
            String senderName,
            String messageText,
            long timestamp,
            boolean mentionsSelf
    ) {
        this.senderName = senderName;
        this.messageText = messageText;
        this.timestamp = timestamp;
        this.mentionsSelf = mentionsSelf;
    }

    public String getSenderName() { return senderName; }
    public String getMessageText() { return messageText; }
    public long getTimestamp() { return timestamp; }
    public boolean isMentionsSelf() { return mentionsSelf; }
}
