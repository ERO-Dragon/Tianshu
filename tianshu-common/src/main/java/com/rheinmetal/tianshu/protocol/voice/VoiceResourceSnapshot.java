package com.rheinmetal.tianshu.protocol.voice;

import java.nio.file.Path;

public record VoiceResourceSnapshot(
        long version,
        Path zhHotwordsFile,
        Path enHotwordsFile,
        VoiceTriggerRegistry triggerRegistry
) {}
