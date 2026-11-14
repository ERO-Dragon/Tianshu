package com.rheinmetal.tianshu.client.api.settings;

import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.function.Consumer;
import java.util.function.Predicate;

public interface ItemActionTemplate<T> {
    ItemActionTemplate<T> button(String id, UiText label, Consumer<T> action);

    ItemActionTemplate<T> button(String id, UiText label, Consumer<T> action, Predicate<T> enabled);

    ItemActionTemplate<T> button(String id, UiText label, Consumer<T> action, Predicate<T> enabled, Predicate<T> visible);

    ItemActionTemplate<T> button(String id, UiText label, SettingsButtonStyle style, Consumer<T> action);

    ItemActionTemplate<T> button(String id, UiText label, SettingsButtonStyle style, Consumer<T> action, Predicate<T> enabled);

    ItemActionTemplate<T> button(String id, UiText label, SettingsButtonStyle style, Consumer<T> action, Predicate<T> enabled, Predicate<T> visible);
}
