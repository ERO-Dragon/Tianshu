package com.rheinmetal.tianshu.client.gui.settings.api;

import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Predicate;

public interface ItemActionTemplate<T> {
    ItemActionTemplate<T> button(String id, Component label, Consumer<T> action);

    ItemActionTemplate<T> button(String id, Component label, Consumer<T> action, Predicate<T> enabled);

    ItemActionTemplate<T> button(String id, Component label, Consumer<T> action, Predicate<T> enabled, Predicate<T> visible);

    ItemActionTemplate<T> button(String id, Component label, SettingsButtonStyle style, Consumer<T> action);

    ItemActionTemplate<T> button(String id, Component label, SettingsButtonStyle style, Consumer<T> action, Predicate<T> enabled);

    ItemActionTemplate<T> button(String id, Component label, SettingsButtonStyle style, Consumer<T> action, Predicate<T> enabled, Predicate<T> visible);
}
