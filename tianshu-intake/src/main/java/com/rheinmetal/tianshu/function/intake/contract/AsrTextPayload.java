package com.rheinmetal.tianshu.function.intake.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record AsrTextPayload(String text, String rawText, int turnId, long sessionId, String inputMode, long createdAt) implements ITianshuPayload {
    public AsrTextPayload {
        if (text == null) text = "";
        if (rawText == null) rawText = text;
        if (inputMode == null) inputMode = "";
    }
}
