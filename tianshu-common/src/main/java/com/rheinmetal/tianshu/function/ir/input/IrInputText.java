package com.rheinmetal.tianshu.function.ir.input;

public record IrInputText(String text, String rawText, int turnId, long sessionId, String source, long createdAt) {
    public IrInputText {
        if (text == null) text = "";
        text = text.trim();
        if (rawText == null || rawText.isBlank()) rawText = text;
        rawText = rawText.trim();
        if (source == null) source = "";
        source = source.trim();
    }

    public boolean blank() {
        return text.isBlank();
    }
}
