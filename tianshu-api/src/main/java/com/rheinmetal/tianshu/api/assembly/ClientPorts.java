package com.rheinmetal.tianshu.api.assembly;

import com.rheinmetal.tianshu.api.config.ModuleConfigStores;
import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerHost;
import com.rheinmetal.tianshu.client.host.ClientAudioDeviceCatalog;
import com.rheinmetal.tianshu.client.host.ClientFilePicker;
import com.rheinmetal.tianshu.client.host.ClientScheduler;
import com.rheinmetal.tianshu.client.host.ClientTextProvider;
import com.rheinmetal.tianshu.client.host.ClientUiHost;
import com.rheinmetal.tianshu.model.NativeModelLoadQueue;

/**
 * The client services a module may use while it builds itself.
 *
 * <p>Each one is a kernel-provided capability with no knowledge of any feature: a module that wants a
 * settings page, a background task or a file dialog asks for these instead of reaching into the host.
 * Adding a service here is a contract change for every provider, so it happens when a module actually
 * needs one.</p>
 */
public record ClientPorts(
        ClientScheduler scheduler,
        ClientUiHost uiHost,
        ClientTextProvider texts,
        ClientFilePicker filePicker,
        SettingsLayerHost settingsLayers,
        ClientAudioDeviceCatalog audioDevices,
        ModuleConfigStores configStores,
        NativeModelLoadQueue modelLoadQueue
) {
}
