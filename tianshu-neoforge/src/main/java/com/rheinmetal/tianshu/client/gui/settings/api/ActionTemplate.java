package com.rheinmetal.tianshu.client.gui.settings.api;

import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

public interface ActionTemplate {
    ActionTemplate button(String id, Component label, Runnable action);

    ActionTemplate button(String id, Component label, Runnable action, BooleanSupplier enabled);

    ActionTemplate button(String id, Component label, Runnable action, BooleanSupplier enabled, BooleanSupplier visible);

    ActionTemplate button(String id, Component label, SettingsButtonStyle style, Runnable action);

    ActionTemplate button(String id, Component label, SettingsButtonStyle style, Runnable action, BooleanSupplier enabled);

    ActionTemplate button(String id, Component label, SettingsButtonStyle style, Runnable action, BooleanSupplier enabled, BooleanSupplier visible);
}


