package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record IrParsePayload(String text, String rawText, int turnId, long sessionId, String source, int repairDepth, boolean llmAllowed) implements ITianshuPayload {
    public IrParsePayload {
        if (text == null) text = "";
        if (rawText == null) rawText = text;
        if (source == null) source = "";
    }
}
