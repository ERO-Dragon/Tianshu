package com.rheinmetal.tianshu.client.gui.settings.model;

import com.rheinmetal.tianshu.client.gui.settings.api.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.gui.settings.api.SettingsListCard;
import com.rheinmetal.tianshu.client.gui.settings.api.TextBlockLevel;

import com.rheinmetal.tianshu.client.ui.UiText;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public sealed interface SettingsTemplateModel permits
        SettingsTemplateModel.Enable,
        SettingsTemplateModel.ToggleGroup,
        SettingsTemplateModel.OptionGroup,
        SettingsTemplateModel.StatusGroup,
        SettingsTemplateModel.ActionGroup,
        SettingsTemplateModel.CompoundGroup,
        SettingsTemplateModel.ListGroup,
        SettingsTemplateModel.CatalogGroup,
        SettingsTemplateModel.Columns,
        SettingsTemplateModel.TextBlock,
        SettingsTemplateModel.Separator {
    String id();

    default BooleanSupplier enabled() {
        return () -> true;
    }

    default BooleanSupplier visible() {
        return () -> true;
    }

    record Enable(String id, UiText label, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record ToggleGroup(String id, UiText title, List<ToggleEntry> entries, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record OptionGroup(String id, UiText title, List<OptionEntry> entries, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record StatusGroup(String id, UiText title, List<StatusEntry> entries, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record ActionGroup(String id, UiText title, List<ActionEntry> entries, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record CompoundGroup(String id, UiText title, List<OptionEntry> options, List<ActionEntry> actions, List<StatusEntry> statuses, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record ListGroup<T>(String id, UiText title, Supplier<List<T>> items, Function<T, UiText> labeler, Function<T, SettingsListCard> carder, Supplier<T> selected, Consumer<T> onSelect, Function<T, List<ItemActionEntry<T>>> itemActions, UiText emptyText, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record CatalogGroup<T>(String id, UiText title, List<OptionEntry> controls, ListGroup<T> list, BooleanSupplier enabled, BooleanSupplier visible, boolean scrollable) implements SettingsTemplateModel {}

    record Columns(String id, List<Double> weights, List<List<SettingsTemplateModel>> columns, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record TextBlock(String id, UiText text, TextBlockLevel level, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record Separator(String id, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    sealed interface OptionEntry permits SelectEntry, TextEntry, SliderEntry {
        String id();

        UiText label();

        BooleanSupplier enabled();

        BooleanSupplier visible();
    }

    record ToggleEntry(String id, UiText label, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier enabled, BooleanSupplier visible) {}

    record SelectEntry<T>(String id, UiText label, List<T> values, Supplier<T> getter, Function<T, UiText> labeler, Consumer<T> setter, BooleanSupplier enabled, BooleanSupplier visible) implements OptionEntry {}

    record TextEntry(String id, UiText label, Supplier<String> getter, Consumer<String> setter, BooleanSupplier enabled, BooleanSupplier visible) implements OptionEntry {}

    record SliderEntry(String id, UiText label, Supplier<Double> getter, double min, double max, Consumer<Double> setter, BooleanSupplier enabled, BooleanSupplier visible) implements OptionEntry {}

    record StatusEntry(String id, UiText label, Supplier<UiText> value, BooleanSupplier enabled, BooleanSupplier visible) {}

    record ActionEntry(String id, UiText label, SettingsButtonStyle style, Runnable action, BooleanSupplier enabled, BooleanSupplier visible) {}

    record ItemActionEntry<T>(String id, UiText label, SettingsButtonStyle style, Consumer<T> action, Predicate<T> enabled, Predicate<T> visible) {}
}
