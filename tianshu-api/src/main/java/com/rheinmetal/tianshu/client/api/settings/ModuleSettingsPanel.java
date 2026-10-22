package com.rheinmetal.tianshu.client.api.settings;

import com.rheinmetal.tianshu.client.settings.session.SettingsValue;

import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface ModuleSettingsPanel {
    ModuleSettingsPanel enable(String id, UiText label, BooleanSupplier getter, Consumer<Boolean> setter);

    default ModuleSettingsPanel enable(String id, UiText label, SettingsValue<Boolean> value) {
        return enable(id, label, value::get, value::set);
    }

    ModuleSettingsPanel enable(String id, UiText label, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier enabled);

    default ModuleSettingsPanel enable(String id, UiText label, SettingsValue<Boolean> value, BooleanSupplier enabled) {
        return enable(id, label, value::get, value::set, enabled);
    }

    ModuleSettingsPanel enable(String id, UiText label, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier enabled, BooleanSupplier visible);

    default ModuleSettingsPanel enable(String id, UiText label, SettingsValue<Boolean> value, BooleanSupplier enabled, BooleanSupplier visible) {
        return enable(id, label, value::get, value::set, enabled, visible);
    }

    ModuleSettingsPanel toggles(String id, UiText title, Consumer<ToggleTemplate> builder);

    ModuleSettingsPanel toggles(String id, UiText title, BooleanSupplier enabled, Consumer<ToggleTemplate> builder);

    ModuleSettingsPanel toggles(String id, UiText title, BooleanSupplier enabled, BooleanSupplier visible, Consumer<ToggleTemplate> builder);

    ModuleSettingsPanel options(String id, UiText title, Consumer<OptionTemplate> builder);

    ModuleSettingsPanel options(String id, UiText title, BooleanSupplier enabled, Consumer<OptionTemplate> builder);

    ModuleSettingsPanel options(String id, UiText title, BooleanSupplier enabled, BooleanSupplier visible, Consumer<OptionTemplate> builder);

    ModuleSettingsPanel status(String id, UiText title, Consumer<StatusTemplate> builder);

    ModuleSettingsPanel status(String id, UiText title, BooleanSupplier enabled, Consumer<StatusTemplate> builder);

    ModuleSettingsPanel status(String id, UiText title, BooleanSupplier enabled, BooleanSupplier visible, Consumer<StatusTemplate> builder);

    ModuleSettingsPanel actions(String id, UiText title, Consumer<ActionTemplate> builder);

    ModuleSettingsPanel actions(String id, UiText title, BooleanSupplier enabled, Consumer<ActionTemplate> builder);

    ModuleSettingsPanel actions(String id, UiText title, BooleanSupplier enabled, BooleanSupplier visible, Consumer<ActionTemplate> builder);

    ModuleSettingsPanel compound(String id, UiText title, Consumer<OptionTemplate> options, Consumer<ActionTemplate> actions, Consumer<StatusTemplate> status);

    ModuleSettingsPanel compound(String id, UiText title, BooleanSupplier enabled, Consumer<OptionTemplate> options, Consumer<ActionTemplate> actions, Consumer<StatusTemplate> status);

    ModuleSettingsPanel compound(String id, UiText title, BooleanSupplier enabled, BooleanSupplier visible, Consumer<OptionTemplate> options, Consumer<ActionTemplate> actions, Consumer<StatusTemplate> status);

    <T> ModuleSettingsPanel list(String id, UiText title, Consumer<ListTemplate<T>> builder);

    <T> ModuleSettingsPanel list(String id, UiText title, BooleanSupplier enabled, Consumer<ListTemplate<T>> builder);

    <T> ModuleSettingsPanel list(String id, UiText title, BooleanSupplier enabled, BooleanSupplier visible, Consumer<ListTemplate<T>> builder);

    <T> ModuleSettingsPanel catalog(String id, UiText title, Consumer<OptionTemplate> controls, Consumer<ListTemplate<T>> list);

    <T> ModuleSettingsPanel catalog(String id, UiText title, BooleanSupplier enabled, Consumer<OptionTemplate> controls, Consumer<ListTemplate<T>> list);

    <T> ModuleSettingsPanel catalog(String id, UiText title, BooleanSupplier enabled, BooleanSupplier visible, Consumer<OptionTemplate> controls, Consumer<ListTemplate<T>> list);

    ModuleSettingsPanel columns(String id, List<Double> weights, Consumer<ColumnTemplate> builder);

    default ModuleSettingsPanel columns(String id, double leftWeight, double rightWeight, Consumer<ColumnTemplate> builder) {
        return columns(id, List.of(leftWeight, rightWeight), builder);
    }

    ModuleSettingsPanel columns(String id, List<Double> weights, BooleanSupplier enabled, Consumer<ColumnTemplate> builder);

    default ModuleSettingsPanel columns(String id, double leftWeight, double rightWeight, BooleanSupplier enabled, Consumer<ColumnTemplate> builder) {
        return columns(id, List.of(leftWeight, rightWeight), enabled, builder);
    }

    ModuleSettingsPanel columns(String id, List<Double> weights, BooleanSupplier enabled, BooleanSupplier visible, Consumer<ColumnTemplate> builder);

    default ModuleSettingsPanel columns(String id, double leftWeight, double rightWeight, BooleanSupplier enabled, BooleanSupplier visible, Consumer<ColumnTemplate> builder) {
        return columns(id, List.of(leftWeight, rightWeight), enabled, visible, builder);
    }

    ModuleSettingsPanel text(String id, UiText text, TextBlockLevel level);

    ModuleSettingsPanel text(String id, UiText text, TextBlockLevel level, BooleanSupplier enabled);

    ModuleSettingsPanel text(String id, UiText text, TextBlockLevel level, BooleanSupplier enabled, BooleanSupplier visible);

    ModuleSettingsPanel separator(String id);

    ModuleSettingsPanel separator(String id, BooleanSupplier enabled);

    ModuleSettingsPanel separator(String id, BooleanSupplier enabled, BooleanSupplier visible);
}


