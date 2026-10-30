package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsScreen;
import com.rheinmetal.tianshu.client.host.ClientUiHost;
import com.rheinmetal.tianshu.client.api.text.UiText;
import net.minecraft.client.Minecraft;

import java.util.Objects;
import java.util.function.Supplier;

public final class NeoForgeClientUiHost implements ClientUiHost {
    private final Supplier<com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsModule> settingsModule;
    private volatile UiText textInputTitle = UiText.key("tianshu.gui.text_input.title");
    private volatile UiText textInputHint = UiText.key("tianshu.gui.text_input.hint");

    public NeoForgeClientUiHost(Supplier<com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsModule> settingsModule) {
        this.settingsModule = Objects.requireNonNull(settingsModule, "settingsModule");
    }

    @Override
    public void setTextInputLabels(UiText title, UiText hint) {
        if (title != null) {
            textInputTitle = title;
        }
        if (hint != null) {
            textInputHint = hint;
        }
    }

    public UiText textInputTitle() {
        return textInputTitle;
    }

    public UiText textInputHint() {
        return textInputHint;
    }

    @Override
    public void openSettings() {
        com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsModule module = settingsModule.get();
        if (module != null) {
            module.openScreen();
        }
    }

    @Override
    public void requestSettingsRefresh() {
        if (Minecraft.getInstance().screen instanceof TianshuSettingsScreen screen) {
            screen.requestRebuildCurrentPage();
        }
    }

    @Override
    public void showStatus(UiText text, long durationMillis) {
        if (Minecraft.getInstance().screen instanceof TianshuSettingsScreen screen) {
            screen.showExternalStatus(text, durationMillis);
        }
    }
}
