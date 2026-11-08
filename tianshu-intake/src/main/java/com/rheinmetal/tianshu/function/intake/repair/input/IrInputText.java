package com.rheinmetal.tianshu.function.intake.repair.input;

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

    /** The same utterance with a different displayed text; identity and provenance are kept. */
    public IrInputText withText(String newText) {
        return new IrInputText(newText, rawText, turnId, sessionId, source, createdAt);
    }
}
