package com.rheinmetal.tianshu.mc.host;

import com.rheinmetal.tianshu.client.host.game.GameScreenKind;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;

/**
 * Classifies the open screen into the port's vocabulary.
 *
 * <p>The port speaks {@link GameScreenKind} because that is the api; this class is the only place that knows which
 * vanilla screen class means which kind, and it belongs to the platform for the same reason.</p>
 */
final class GameScreenClassifier {

    GameScreenKind classify(Screen screen) {
        if (screen == null) {
            return GameScreenKind.NONE;
        }
        if (screen instanceof ChatScreen) {
            return GameScreenKind.CHAT;
        }
        if (screen instanceof PauseScreen) {
            return GameScreenKind.PAUSE;
        }
        if (screen instanceof OptionsScreen) {
            return GameScreenKind.SETTINGS;
        }
        if (screen instanceof InventoryScreen) {
            return GameScreenKind.INVENTORY;
        }
        if (screen instanceof AbstractContainerScreen<?>) {
            return GameScreenKind.CONTAINER;
        }
        return GameScreenKind.OTHER;
    }
}
