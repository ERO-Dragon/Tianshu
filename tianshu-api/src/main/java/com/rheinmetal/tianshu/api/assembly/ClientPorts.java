package com.rheinmetal.tianshu.api.assembly;

import com.rheinmetal.tianshu.api.client.host.hud.HudHost;
import com.rheinmetal.tianshu.api.config.ModuleConfigStores;
import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerHost;
import com.rheinmetal.tianshu.client.host.ClientAudioDeviceCatalog;
import com.rheinmetal.tianshu.api.server.ClientServerActionPort;
import com.rheinmetal.tianshu.client.host.ClientFilePicker;
import com.rheinmetal.tianshu.client.host.ClientPresentationPort;
import com.rheinmetal.tianshu.client.host.ClientScheduler;
import com.rheinmetal.tianshu.client.host.ClientTextProvider;
import com.rheinmetal.tianshu.client.host.ClientUiHost;
import com.rheinmetal.tianshu.client.host.DiagnosticChatCatalogue;
import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.model.NativeModelLoadQueue;

import java.util.function.BooleanSupplier;

/**
 * The client services a module may use while it builds itself.
 *
 * <p>Each one is a kernel-provided capability with no knowledge of any feature: a module that wants a
 * settings page, a background task or a file dialog asks for these instead of reaching into the host.
 * Adding a service here is a contract change for every provider, so it happens when a module actually
 * needs one.</p>
 *
 * <p>{@code gameContext} reads the game, {@code hud} draws continuously, and {@code presentation} fires
 * one-shot local effects (chat line, action bar, toast, title, sound). None of them carries a Minecraft type,
 * which is what lets the feature stay universal.</p>
 */
public record ClientPorts(
        ClientScheduler scheduler,
        ClientUiHost uiHost,
        ClientTextProvider texts,
        ClientFilePicker filePicker,
        SettingsLayerHost settingsLayers,
        ClientAudioDeviceCatalog audioDevices,
        ModuleConfigStores configStores,
        NativeModelLoadQueue modelLoadQueue,
        HudHost hud,
        ClientGameContextPort gameContext,
        BooleanSupplier debugEnabled,
        DiagnosticChatCatalogue diagnosticChat,
        ClientPresentationPort presentation,
        ClientServerActionPort serverActions
) {
}
