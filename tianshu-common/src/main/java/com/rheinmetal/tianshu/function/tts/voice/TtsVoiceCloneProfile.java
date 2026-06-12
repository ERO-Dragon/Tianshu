package com.rheinmetal.tianshu.function.tts.voice;

import java.nio.file.Path;

public record TtsVoiceCloneProfile(
        String voiceId,
        String ownerModuleId,
        Path samplePath,
        TtsReferenceAudio referenceAudio,
        String referenceText,
        long lastModifiedMillis,
        long sizeBytes,
        long loadedAtMillis
) {
    public TtsVoiceCloneProfile {
        voiceId = normalize(voiceId);
        ownerModuleId = normalize(ownerModuleId);
        if (samplePath == null) {
            throw new IllegalArgumentException("samplePath cannot be null");
        }
        samplePath = samplePath.toAbsolutePath().normalize();
        referenceAudio = referenceAudio == null ? new TtsReferenceAudio(new float[0], 1) : referenceAudio;
        referenceText = referenceText == null ? "" : referenceText.trim();
        lastModifiedMillis = Math.max(0L, lastModifiedMillis);
        sizeBytes = Math.max(0L, sizeBytes);
        loadedAtMillis = loadedAtMillis > 0L ? loadedAtMillis : System.currentTimeMillis();
    }

    public String cacheKey() {
        return voiceId + "|" + samplePath + "|" + lastModifiedMillis + "|" + sizeBytes;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
