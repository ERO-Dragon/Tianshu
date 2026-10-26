package com.rheinmetal.tianshu.neoforge.ui;

import com.rheinmetal.tianshu.api.assembly.ClientKeyBindings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;

/**
 * Opens the vanilla controls screen from a module's settings row.
 *
 * <p>The binding itself is a Minecraft object, so only this layer may touch it. A module asks for the screen
 * and the platform shows it — which also means a player who rebinds here and a player who rebinds from the
 * controls screen change the very same mapping, not two copies.</p>
 */
public final class NeoForgeClientKeyBindings implements ClientKeyBindings {

    @Override
    public void openRebindScreen(String keyMappingName) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        client.setScreen(new KeyBindsScreen(client.screen, client.options));
    }
}
