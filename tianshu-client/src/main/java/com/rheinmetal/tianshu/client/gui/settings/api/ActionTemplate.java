package com.rheinmetal.tianshu.client.gui.settings.api;

import com.rheinmetal.tianshu.client.ui.UiText;

import java.util.function.BooleanSupplier;

public interface ActionTemplate {
    ActionTemplate button(String id, UiText label, Runnable action);

    ActionTemplate button(String id, UiText label, Runnable action, BooleanSupplier enabled);

    ActionTemplate button(String id, UiText label, Runnable action, BooleanSupplier enabled, BooleanSupplier visible);

    ActionTemplate button(String id, UiText label, SettingsButtonStyle style, Runnable action);

    ActionTemplate button(String id, UiText label, SettingsButtonStyle style, Runnable action, BooleanSupplier enabled);

    ActionTemplate button(String id, UiText label, SettingsButtonStyle style, Runnable action, BooleanSupplier enabled, BooleanSupplier visible);
}


