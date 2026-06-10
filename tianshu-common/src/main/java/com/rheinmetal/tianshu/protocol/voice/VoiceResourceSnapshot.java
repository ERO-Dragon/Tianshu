package com.rheinmetal.tianshu.protocol.voice;

import java.nio.file.Path;

public record VoiceResourceSnapshot(
        long version,
        Path zhHotwordsFile,
        Path enHotwordsFile,
        String hotwordFingerprint,
        VoiceTriggerRegistry triggerRegistry
) {
    public VoiceResourceSnapshot(long version, Path zhHotwordsFile, Path enHotwordsFile, VoiceTriggerRegistry triggerRegistry) {
        this(version, zhHotwordsFile, enHotwordsFile, "", triggerRegistry);
    }

    public VoiceResourceSnapshot {
        hotwordFingerprint = hotwordFingerprint == null ? "" : hotwordFingerprint.trim();
    }
}
