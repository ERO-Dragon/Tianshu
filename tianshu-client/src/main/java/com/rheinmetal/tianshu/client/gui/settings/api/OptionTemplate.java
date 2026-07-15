package com.rheinmetal.tianshu.client.gui.settings.api;

import com.rheinmetal.tianshu.client.gui.settings.session.SettingsValue;

import com.rheinmetal.tianshu.client.ui.UiText;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public interface OptionTemplate {
    <T> OptionTemplate select(String id, UiText label, List<T> values, Supplier<T> getter, Function<T, UiText> labeler, Consumer<T> setter);

    default <T> OptionTemplate select(String id, UiText label, List<T> values, SettingsValue<T> value, Function<T, UiText> labeler) {
        return select(id, label, values, value::get, labeler, value::set);
    }

    <T> OptionTemplate select(String id, UiText label, List<T> values, Supplier<T> getter, Function<T, UiText> labeler, Consumer<T> setter, BooleanSupplier enabled);

    default <T> OptionTemplate select(String id, UiText label, List<T> values, SettingsValue<T> value, Function<T, UiText> labeler, BooleanSupplier enabled) {
        return select(id, label, values, value::get, labeler, value::set, enabled);
    }

    <T> OptionTemplate select(String id, UiText label, List<T> values, Supplier<T> getter, Function<T, UiText> labeler, Consumer<T> setter, BooleanSupplier enabled, BooleanSupplier visible);

    default <T> OptionTemplate select(String id, UiText label, List<T> values, SettingsValue<T> value, Function<T, UiText> labeler, BooleanSupplier enabled, BooleanSupplier visible) {
        return select(id, label, values, value::get, labeler, value::set, enabled, visible);
    }

    OptionTemplate text(String id, UiText label, Supplier<String> getter, Consumer<String> setter);

    default OptionTemplate text(String id, UiText label, SettingsValue<String> value) {
        return text(id, label, value::get, value::set);
    }

    OptionTemplate text(String id, UiText label, Supplier<String> getter, Consumer<String> setter, BooleanSupplier enabled);

    default OptionTemplate text(String id, UiText label, SettingsValue<String> value, BooleanSupplier enabled) {
        return text(id, label, value::get, value::set, enabled);
    }

    OptionTemplate text(String id, UiText label, Supplier<String> getter, Consumer<String> setter, BooleanSupplier enabled, BooleanSupplier visible);

    default OptionTemplate text(String id, UiText label, SettingsValue<String> value, BooleanSupplier enabled, BooleanSupplier visible) {
        return text(id, label, value::get, value::set, enabled, visible);
    }

    OptionTemplate slider(String id, UiText label, Supplier<Double> getter, double min, double max, Consumer<Double> setter);

    default OptionTemplate slider(String id, UiText label, SettingsValue<Double> value, double min, double max) {
        return slider(id, label, value::get, min, max, value::set);
    }

    OptionTemplate slider(String id, UiText label, Supplier<Double> getter, double min, double max, Consumer<Double> setter, BooleanSupplier enabled);

    default OptionTemplate slider(String id, UiText label, SettingsValue<Double> value, double min, double max, BooleanSupplier enabled) {
        return slider(id, label, value::get, min, max, value::set, enabled);
    }

    OptionTemplate slider(String id, UiText label, Supplier<Double> getter, double min, double max, Consumer<Double> setter, BooleanSupplier enabled, BooleanSupplier visible);

    default OptionTemplate slider(String id, UiText label, SettingsValue<Double> value, double min, double max, BooleanSupplier enabled, BooleanSupplier visible) {
        return slider(id, label, value::get, min, max, value::set, enabled, visible);
    }
}


