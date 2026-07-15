package com.rheinmetal.tianshu.function.tts.settings;

import java.nio.file.Path;

public interface TtsConfiguration {
    boolean isTtsEnabled();

    String getCustomTtsName();

    Path getTtsBasePath();

    Path getVoiceLibraryPath();
}
