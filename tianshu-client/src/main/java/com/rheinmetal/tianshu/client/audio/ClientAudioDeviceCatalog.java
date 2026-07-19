package com.rheinmetal.tianshu.client.audio;

import java.util.List;

public interface ClientAudioDeviceCatalog {
    List<String> currentMicNames();

    void refreshMicNames(Runnable onComplete);
}
