package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record ChatAssistantSendPayload(String text, long sessionId) implements ITianshuPayload {
    public ChatAssistantSendPayload {
        if (text == null) text = "";
        text = text.trim();
    }
}
