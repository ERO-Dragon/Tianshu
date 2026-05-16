package com.rheinmetal.tianshu.function.tts.text;

public final class TtsTextNormalizer {
    public String normalize(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }
        String cleaned = rawText.replaceAll("<(?:think|reasoning|reflection)[^>]*>[\\s\\S]*?</(?:think|reasoning|reflection)>", "");
        cleaned = cleaned.replaceAll("<(?:think|reasoning|reflection)[^>]*/>", "");
        cleaned = cleaned.replace('\r', ' ').replace('\n', ' ');
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }
}
