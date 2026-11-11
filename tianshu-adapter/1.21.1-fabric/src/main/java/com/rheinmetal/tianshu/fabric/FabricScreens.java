package com.rheinmetal.tianshu.fabric;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/**
 * Adding a widget to a screen that is already built.
 *
 * <p>The host computes the pause-menu slot and hands the button over; the loader decides how it lands there. On
 * Fabric that is {@link ScreenWidgetAccess}, which every screen implements through a mixin, kept behind this one
 * call so nothing else in the adapter depends on a mixin being present.</p>
 */
final class FabricScreens {

    static void add(Screen screen, Button button) {
        ((ScreenWidgetAccess) screen).tianshu$addWidget(button);
    }

    private FabricScreens() {
    }
}