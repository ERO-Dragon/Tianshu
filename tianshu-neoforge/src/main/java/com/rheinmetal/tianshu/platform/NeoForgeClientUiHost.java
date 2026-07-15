package com.rheinmetal.tianshu.platform;

import com.rheinmetal.tianshu.client.gui.settings.screen.TianshuSettingsScreen;
import com.rheinmetal.tianshu.client.host.ClientUiHost;
import com.rheinmetal.tianshu.client.ui.UiText;
import net.minecraft.client.Minecraft;

import java.util.Objects;
import java.util.function.Supplier;

public final class NeoForgeClientUiHost implements ClientUiHost {
    private final Supplier<com.rheinmetal.tianshu.client.gui.settings.module.TianshuSettingsModule> settingsModule;

    public NeoForgeClientUiHost(Supplier<com.rheinmetal.tianshu.client.gui.settings.module.TianshuSettingsModule> settingsModule) {
        this.settingsModule = Objects.requireNonNull(settingsModule, "settingsModule");
    }

    @Override
    public void openSettings() {
        com.rheinmetal.tianshu.client.gui.settings.module.TianshuSettingsModule module = settingsModule.get();
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
