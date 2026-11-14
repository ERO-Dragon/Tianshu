package com.rheinmetal.tianshu.client.host;

import java.util.List;

/**
 * The microphone list and the current selection.
 *
 * <p>A module that owns the microphone preference can restore it through this port instead of the host
 * reading that module's config. The platform only knows device names.</p>
 */
public interface ClientAudioDeviceCatalog {

    List<String> currentMicNames();

    void refreshMicNames(Runnable onComplete);

    /** Selects a device by name; a blank or unknown name leaves the platform default in place. */
    void selectMic(String micName);
}
