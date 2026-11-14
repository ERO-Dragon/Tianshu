package com.rheinmetal.tianshu.function.echo.voice;

import java.nio.file.Path;

public record TtsVoiceCloneProfile(
        String voiceId,
        String ownerModuleId,
        Path samplePath,
        TtsReferenceAudio referenceAudio,
        String referenceText
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
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
