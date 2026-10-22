package com.rheinmetal.tianshu.function.intake.repair;

record IrCompiledVoiceWord(String rawText, String normalizedText) {
    IrCompiledVoiceWord {
        if (rawText == null) rawText = "";
        if (normalizedText == null) normalizedText = "";
        rawText = rawText.trim();
        normalizedText = normalizedText.trim();
    }

    boolean isEmpty() {
        return rawText.isBlank() || normalizedText.isBlank();
    }
}
