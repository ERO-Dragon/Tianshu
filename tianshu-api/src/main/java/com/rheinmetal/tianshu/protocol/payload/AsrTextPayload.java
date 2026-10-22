package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record AsrTextPayload(String text, String rawText, int turnId, long sessionId, String inputMode, long createdAt) implements ITianshuPayload {
    public AsrTextPayload {
        if (text == null) text = "";
        if (rawText == null) rawText = text;
        if (inputMode == null) inputMode = "";
    }
}
