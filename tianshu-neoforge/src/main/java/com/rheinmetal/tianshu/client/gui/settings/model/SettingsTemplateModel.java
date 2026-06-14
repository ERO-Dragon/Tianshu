package com.rheinmetal.tianshu.client.gui.settings.model;

import com.rheinmetal.tianshu.client.gui.settings.api.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.gui.settings.api.SettingsListCard;
import com.rheinmetal.tianshu.client.gui.settings.api.TextBlockLevel;

import net.minecraft.network.chat.Component;

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

    record Enable(String id, Component label, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record ToggleGroup(String id, Component title, List<ToggleEntry> entries, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record OptionGroup(String id, Component title, List<OptionEntry> entries, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record StatusGroup(String id, Component title, List<StatusEntry> entries, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record ActionGroup(String id, Component title, List<ActionEntry> entries, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record ListGroup<T>(String id, Component title, Supplier<List<T>> items, Function<T, Component> labeler, Function<T, SettingsListCard> carder, Supplier<T> selected, Consumer<T> onSelect, Function<T, List<ItemActionEntry<T>>> itemActions, Component emptyText, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record CatalogGroup<T>(String id, Component title, List<OptionEntry> controls, ListGroup<T> list, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record Columns(String id, List<Double> weights, List<List<SettingsTemplateModel>> columns, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record TextBlock(String id, Component text, TextBlockLevel level, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    record Separator(String id, BooleanSupplier enabled, BooleanSupplier visible) implements SettingsTemplateModel {}

    sealed interface OptionEntry permits SelectEntry, TextEntry, SliderEntry {
        String id();

        Component label();

        BooleanSupplier enabled();

        BooleanSupplier visible();
    }

    record ToggleEntry(String id, Component label, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier enabled, BooleanSupplier visible) {}

    record SelectEntry<T>(String id, Component label, List<T> values, Supplier<T> getter, Function<T, Component> labeler, Consumer<T> setter, BooleanSupplier enabled, BooleanSupplier visible) implements OptionEntry {}

    record TextEntry(String id, Component label, Supplier<String> getter, Consumer<String> setter, BooleanSupplier enabled, BooleanSupplier visible) implements OptionEntry {}

    record SliderEntry(String id, Component label, Supplier<Double> getter, double min, double max, Consumer<Double> setter, BooleanSupplier enabled, BooleanSupplier visible) implements OptionEntry {}

    record StatusEntry(String id, Component label, Supplier<Component> value, BooleanSupplier enabled, BooleanSupplier visible) {}

    record ActionEntry(String id, Component label, SettingsButtonStyle style, Runnable action, BooleanSupplier enabled, BooleanSupplier visible) {}

    record ItemActionEntry<T>(String id, Component label, SettingsButtonStyle style, Consumer<T> action, Predicate<T> enabled, Predicate<T> visible) {}
}
