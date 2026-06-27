package com.rheinmetal.tianshu.client.presence;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.PauseScreen;

public final class PresenceScreenClassifier {
    public PresenceScreenKind classify(Screen screen) {
        if (screen == null) {
            return PresenceScreenKind.NONE;
        }
        if (screen instanceof ChatScreen) {
            return PresenceScreenKind.CHAT;
        }
        if (screen instanceof PauseScreen) {
            return PresenceScreenKind.PAUSE;
        }
        if (screen instanceof OptionsScreen) {
            return PresenceScreenKind.SETTINGS;
        }
        if (screen instanceof InventoryScreen) {
            return PresenceScreenKind.INVENTORY;
        }
        if (screen instanceof AbstractContainerScreen<?>) {
            return PresenceScreenKind.CONTAINER;
        }
        return PresenceScreenKind.OTHER;
    }
}
