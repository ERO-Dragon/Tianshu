package com.rheinmetal.tianshu.client.gui.settings.api;

import com.rheinmetal.tianshu.client.gui.settings.session.SettingsValue;

import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface ToggleTemplate {
    ToggleTemplate toggle(String id, Component label, BooleanSupplier getter, Consumer<Boolean> setter);

    default ToggleTemplate toggle(String id, Component label, SettingsValue<Boolean> value) {
        return toggle(id, label, value::get, value::set);
    }

    ToggleTemplate toggle(String id, Component label, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier enabled);

    default ToggleTemplate toggle(String id, Component label, SettingsValue<Boolean> value, BooleanSupplier enabled) {
        return toggle(id, label, value::get, value::set, enabled);
    }

    ToggleTemplate toggle(String id, Component label, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier enabled, BooleanSupplier visible);

    default ToggleTemplate toggle(String id, Component label, SettingsValue<Boolean> value, BooleanSupplier enabled, BooleanSupplier visible) {
        return toggle(id, label, value::get, value::set, enabled, visible);
    }
}


