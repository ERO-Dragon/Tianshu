package com.rheinmetal.tianshu.function.intake.recognition;

public record AsrRecognitionResult(
        String text,
        String rawText,
        long sessionId,
        String inputMode
) {
    public AsrRecognitionResult {
        if (text == null) text = "";
        if (rawText == null) rawText = text;
        if (inputMode == null) inputMode = "";
    }

    public boolean hasText() {
        return !text.isBlank();
    }
}
