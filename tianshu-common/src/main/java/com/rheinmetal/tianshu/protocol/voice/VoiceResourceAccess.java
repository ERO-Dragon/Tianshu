package com.rheinmetal.tianshu.protocol.voice;

import java.nio.file.Path;
import java.util.function.Consumer;

public interface VoiceResourceAccess {
    VoiceResourceSnapshot snapshot();

    Path resolveHotwordsFile(String language);

    void addChangeListener(Consumer<VoiceResourceSnapshot> listener);

    void removeChangeListener(Consumer<VoiceResourceSnapshot> listener);
}
