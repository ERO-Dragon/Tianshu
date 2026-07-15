package com.rheinmetal.tianshu.protocol.voice;

import java.nio.file.Path;

@FunctionalInterface
public interface VoiceResourceConfiguration {
    Path getAsrBasePath();
}
