package com.rheinmetal.tianshu.function.tts.runtime;

public record TtsModelSnapshot(
        boolean configured,
        boolean catalogMatched,
        boolean directoryExists,
        boolean hasContent,
        String modelName,
        String displayName,
        String modelId,
        String engineType,
        String tier,
        String performance,
        boolean supportsVoiceClone,
        boolean supportsSpeakerSelection,
        boolean downloadPaused,
        String modelDirectory,
        long updatedAtMillis
) {
    public TtsModelSnapshot {
        modelName = modelName == null ? "" : modelName.trim();
        displayName = displayName == null ? "" : displayName.trim();
        modelId = modelId == null ? "" : modelId.trim();
        engineType = engineType == null ? "" : engineType.trim();
        tier = tier == null ? "" : tier.trim();
        performance = performance == null ? "" : performance.trim();
        modelDirectory = modelDirectory == null ? "" : modelDirectory.trim();
        updatedAtMillis = updatedAtMillis > 0L ? updatedAtMillis : System.currentTimeMillis();
    }

    public static TtsModelSnapshot unconfigured() {
        return new TtsModelSnapshot(false, false, false, false, "", "", "", "", "", "", false, false, false, "", System.currentTimeMillis());
    }
}
