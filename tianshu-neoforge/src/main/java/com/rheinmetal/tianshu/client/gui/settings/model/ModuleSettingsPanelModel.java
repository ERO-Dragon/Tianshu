package com.rheinmetal.tianshu.client.gui.settings.model;

import com.rheinmetal.tianshu.client.gui.settings.api.ActionTemplate;
import com.rheinmetal.tianshu.client.gui.settings.api.ItemActionTemplate;
import com.rheinmetal.tianshu.client.gui.settings.api.ListTemplate;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate;
import com.rheinmetal.tianshu.client.gui.settings.api.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.gui.settings.api.StatusTemplate;
import com.rheinmetal.tianshu.client.gui.settings.api.TextBlockLevel;
import com.rheinmetal.tianshu.client.gui.settings.api.ToggleTemplate;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class ModuleSettingsPanelModel implements ModuleSettingsPanel {
    private static final BooleanSupplier ALWAYS_ENABLED = () -> true;
    private static final BooleanSupplier ALWAYS_VISIBLE = () -> true;
    private static final Predicate<Object> ALWAYS_ITEM_ENABLED = item -> true;
    private static final Predicate<Object> ALWAYS_ITEM_VISIBLE = item -> true;

    private final List<SettingsTemplateModel> templates = new ArrayList<>();

    public List<SettingsTemplateModel> templates() {
        return List.copyOf(templates);
    }

    @Override
    public ModuleSettingsPanel enable(String id, Component label, BooleanSupplier getter, Consumer<Boolean> setter) {
        return enable(id, label, getter, setter, ALWAYS_ENABLED, ALWAYS_VISIBLE);
    }

    @Override
    public ModuleSettingsPanel enable(String id, Component label, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier enabled) {
        return enable(id, label, getter, setter, enabled, ALWAYS_VISIBLE);
    }

    @Override
    public ModuleSettingsPanel enable(String id, Component label, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier enabled, BooleanSupplier visible) {
        templates.add(new SettingsTemplateModel.Enable(id, label, getter, setter, enabled, visible));
        return this;
    }

    @Override
    public ModuleSettingsPanel toggles(String id, Component title, Consumer<ToggleTemplate> builder) {
        return toggles(id, title, ALWAYS_ENABLED, ALWAYS_VISIBLE, builder);
    }

    @Override
    public ModuleSettingsPanel toggles(String id, Component title, BooleanSupplier enabled, Consumer<ToggleTemplate> builder) {
        return toggles(id, title, enabled, ALWAYS_VISIBLE, builder);
    }

    @Override
    public ModuleSettingsPanel toggles(String id, Component title, BooleanSupplier enabled, BooleanSupplier visible, Consumer<ToggleTemplate> builder) {
        ToggleGroup group = new ToggleGroup(id, title);
        builder.accept(group);
        templates.add(group.toModel(enabled, visible));
        return this;
    }

    @Override
    public ModuleSettingsPanel options(String id, Component title, Consumer<OptionTemplate> builder) {
        return options(id, title, ALWAYS_ENABLED, ALWAYS_VISIBLE, builder);
    }

    @Override
    public ModuleSettingsPanel options(String id, Component title, BooleanSupplier enabled, Consumer<OptionTemplate> builder) {
        return options(id, title, enabled, ALWAYS_VISIBLE, builder);
    }

    @Override
    public ModuleSettingsPanel options(String id, Component title, BooleanSupplier enabled, BooleanSupplier visible, Consumer<OptionTemplate> builder) {
        OptionGroup group = new OptionGroup(id, title);
        builder.accept(group);
        templates.add(group.toModel(enabled, visible));
        return this;
    }

    @Override
    public ModuleSettingsPanel status(String id, Component title, Consumer<StatusTemplate> builder) {
        return status(id, title, ALWAYS_ENABLED, ALWAYS_VISIBLE, builder);
    }

    @Override
    public ModuleSettingsPanel status(String id, Component title, BooleanSupplier enabled, Consumer<StatusTemplate> builder) {
        return status(id, title, enabled, ALWAYS_VISIBLE, builder);
    }

    @Override
    public ModuleSettingsPanel status(String id, Component title, BooleanSupplier enabled, BooleanSupplier visible, Consumer<StatusTemplate> builder) {
        StatusGroup group = new StatusGroup(id, title);
        builder.accept(group);
        templates.add(group.toModel(enabled, visible));
        return this;
    }

    @Override
    public ModuleSettingsPanel actions(String id, Component title, Consumer<ActionTemplate> builder) {
        return actions(id, title, ALWAYS_ENABLED, ALWAYS_VISIBLE, builder);
    }

    @Override
    public ModuleSettingsPanel actions(String id, Component title, BooleanSupplier enabled, Consumer<ActionTemplate> builder) {
        return actions(id, title, enabled, ALWAYS_VISIBLE, builder);
    }

    @Override
    public ModuleSettingsPanel actions(String id, Component title, BooleanSupplier enabled, BooleanSupplier visible, Consumer<ActionTemplate> builder) {
        ActionGroup group = new ActionGroup(id, title);
        builder.accept(group);
        templates.add(group.toModel(enabled, visible));
        return this;
    }

    @Override
    public <T> ModuleSettingsPanel list(String id, Component title, Consumer<ListTemplate<T>> builder) {
        return list(id, title, ALWAYS_ENABLED, ALWAYS_VISIBLE, builder);
    }

    @Override
    public <T> ModuleSettingsPanel list(String id, Component title, BooleanSupplier enabled, Consumer<ListTemplate<T>> builder) {
        return list(id, title, enabled, ALWAYS_VISIBLE, builder);
    }

    @Override
    public <T> ModuleSettingsPanel list(String id, Component title, BooleanSupplier enabled, BooleanSupplier visible, Consumer<ListTemplate<T>> builder) {
        ListGroup<T> group = new ListGroup<>(id, title);
        builder.accept(group);
        templates.add(group.toModel(enabled, visible));
        return this;
    }

    @Override
    public ModuleSettingsPanel text(String id, Component text, TextBlockLevel level) {
        return text(id, text, level, ALWAYS_ENABLED, ALWAYS_VISIBLE);
    }

    @Override
    public ModuleSettingsPanel text(String id, Component text, TextBlockLevel level, BooleanSupplier enabled) {
        return text(id, text, level, enabled, ALWAYS_VISIBLE);
    }

    @Override
    public ModuleSettingsPanel text(String id, Component text, TextBlockLevel level, BooleanSupplier enabled, BooleanSupplier visible) {
        templates.add(new SettingsTemplateModel.TextBlock(id, text, level, enabled, visible));
        return this;
    }

    @Override
    public ModuleSettingsPanel separator(String id) {
        return separator(id, ALWAYS_ENABLED, ALWAYS_VISIBLE);
    }

    @Override
    public ModuleSettingsPanel separator(String id, BooleanSupplier enabled) {
        return separator(id, enabled, ALWAYS_VISIBLE);
    }

    @Override
    public ModuleSettingsPanel separator(String id, BooleanSupplier enabled, BooleanSupplier visible) {
        templates.add(new SettingsTemplateModel.Separator(id, enabled, visible));
        return this;
    }

    private static final class ToggleGroup implements ToggleTemplate {
        private final String id;
        private final Component title;
        private final List<SettingsTemplateModel.ToggleEntry> entries = new ArrayList<>();

        private ToggleGroup(String id, Component title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public ToggleTemplate toggle(String id, Component label, BooleanSupplier getter, Consumer<Boolean> setter) {
            return toggle(id, label, getter, setter, ALWAYS_ENABLED, ALWAYS_VISIBLE);
        }

        @Override
        public ToggleTemplate toggle(String id, Component label, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier enabled) {
            return toggle(id, label, getter, setter, enabled, ALWAYS_VISIBLE);
        }

        @Override
        public ToggleTemplate toggle(String id, Component label, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier enabled, BooleanSupplier visible) {
            entries.add(new SettingsTemplateModel.ToggleEntry(id, label, getter, setter, enabled, visible));
            return this;
        }

        private SettingsTemplateModel.ToggleGroup toModel(BooleanSupplier enabled, BooleanSupplier visible) {
            return new SettingsTemplateModel.ToggleGroup(id, title, List.copyOf(entries), enabled, visible);
        }
    }

    private static final class OptionGroup implements OptionTemplate {
        private final String id;
        private final Component title;
        private final List<SettingsTemplateModel.OptionEntry> entries = new ArrayList<>();

        private OptionGroup(String id, Component title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public <T> OptionTemplate select(String id, Component label, List<T> values, Supplier<T> getter, Function<T, Component> labeler, Consumer<T> setter) {
            return select(id, label, values, getter, labeler, setter, ALWAYS_ENABLED, ALWAYS_VISIBLE);
        }

        @Override
        public <T> OptionTemplate select(String id, Component label, List<T> values, Supplier<T> getter, Function<T, Component> labeler, Consumer<T> setter, BooleanSupplier enabled) {
            return select(id, label, values, getter, labeler, setter, enabled, ALWAYS_VISIBLE);
        }

        @Override
        public <T> OptionTemplate select(String id, Component label, List<T> values, Supplier<T> getter, Function<T, Component> labeler, Consumer<T> setter, BooleanSupplier enabled, BooleanSupplier visible) {
            entries.add(new SettingsTemplateModel.SelectEntry<>(id, label, List.copyOf(values), getter, labeler, setter, enabled, visible));
            return this;
        }

        @Override
        public OptionTemplate text(String id, Component label, Supplier<String> getter, Consumer<String> setter) {
            return text(id, label, getter, setter, ALWAYS_ENABLED, ALWAYS_VISIBLE);
        }

        @Override
        public OptionTemplate text(String id, Component label, Supplier<String> getter, Consumer<String> setter, BooleanSupplier enabled) {
            return text(id, label, getter, setter, enabled, ALWAYS_VISIBLE);
        }

        @Override
        public OptionTemplate text(String id, Component label, Supplier<String> getter, Consumer<String> setter, BooleanSupplier enabled, BooleanSupplier visible) {
            entries.add(new SettingsTemplateModel.TextEntry(id, label, getter, setter, enabled, visible));
            return this;
        }

        @Override
        public OptionTemplate slider(String id, Component label, Supplier<Double> getter, double min, double max, Consumer<Double> setter) {
            return slider(id, label, getter, min, max, setter, ALWAYS_ENABLED, ALWAYS_VISIBLE);
        }

        @Override
        public OptionTemplate slider(String id, Component label, Supplier<Double> getter, double min, double max, Consumer<Double> setter, BooleanSupplier enabled) {
            return slider(id, label, getter, min, max, setter, enabled, ALWAYS_VISIBLE);
        }

        @Override
        public OptionTemplate slider(String id, Component label, Supplier<Double> getter, double min, double max, Consumer<Double> setter, BooleanSupplier enabled, BooleanSupplier visible) {
            entries.add(new SettingsTemplateModel.SliderEntry(id, label, getter, min, max, setter, enabled, visible));
            return this;
        }

        private SettingsTemplateModel.OptionGroup toModel(BooleanSupplier enabled, BooleanSupplier visible) {
            return new SettingsTemplateModel.OptionGroup(id, title, List.copyOf(entries), enabled, visible);
        }
    }

    private static final class StatusGroup implements StatusTemplate {
        private final String id;
        private final Component title;
        private final List<SettingsTemplateModel.StatusEntry> entries = new ArrayList<>();

        private StatusGroup(String id, Component title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public StatusTemplate row(String id, Component label, Supplier<Component> value) {
            return row(id, label, value, ALWAYS_ENABLED, ALWAYS_VISIBLE);
        }

        @Override
        public StatusTemplate row(String id, Component label, Supplier<Component> value, BooleanSupplier enabled) {
            return row(id, label, value, enabled, ALWAYS_VISIBLE);
        }

        @Override
        public StatusTemplate row(String id, Component label, Supplier<Component> value, BooleanSupplier enabled, BooleanSupplier visible) {
            entries.add(new SettingsTemplateModel.StatusEntry(id, label, value, enabled, visible));
            return this;
        }

        private SettingsTemplateModel.StatusGroup toModel(BooleanSupplier enabled, BooleanSupplier visible) {
            return new SettingsTemplateModel.StatusGroup(id, title, List.copyOf(entries), enabled, visible);
        }
    }

    private static final class ActionGroup implements ActionTemplate {
        private final String id;
        private final Component title;
        private final List<SettingsTemplateModel.ActionEntry> entries = new ArrayList<>();

        private ActionGroup(String id, Component title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public ActionTemplate button(String id, Component label, Runnable action) {
            return button(id, label, SettingsButtonStyle.NORMAL, action, ALWAYS_ENABLED, ALWAYS_VISIBLE);
        }

        @Override
        public ActionTemplate button(String id, Component label, Runnable action, BooleanSupplier enabled) {
            return button(id, label, SettingsButtonStyle.NORMAL, action, enabled, ALWAYS_VISIBLE);
        }

        @Override
        public ActionTemplate button(String id, Component label, Runnable action, BooleanSupplier enabled, BooleanSupplier visible) {
            return button(id, label, SettingsButtonStyle.NORMAL, action, enabled, visible);
        }

        @Override
        public ActionTemplate button(String id, Component label, SettingsButtonStyle style, Runnable action) {
            return button(id, label, style, action, ALWAYS_ENABLED, ALWAYS_VISIBLE);
        }

        @Override
        public ActionTemplate button(String id, Component label, SettingsButtonStyle style, Runnable action, BooleanSupplier enabled) {
            return button(id, label, style, action, enabled, ALWAYS_VISIBLE);
        }

        @Override
        public ActionTemplate button(String id, Component label, SettingsButtonStyle style, Runnable action, BooleanSupplier enabled, BooleanSupplier visible) {
            entries.add(new SettingsTemplateModel.ActionEntry(id, label, style, action, enabled, visible));
            return this;
        }

        private SettingsTemplateModel.ActionGroup toModel(BooleanSupplier enabled, BooleanSupplier visible) {
            return new SettingsTemplateModel.ActionGroup(id, title, List.copyOf(entries), enabled, visible);
        }
    }

    private static final class ListGroup<T> implements ListTemplate<T> {
        private final String id;
        private final Component title;
        private BiConsumer<T, ItemActionTemplate<T>> itemActionsBuilder = (item, actions) -> {};
        private Supplier<List<T>> items = List::of;
        private Function<T, Component> labeler = item -> Component.literal(String.valueOf(item));
        private Supplier<T> selected = () -> null;
        private Consumer<T> onSelect = item -> {};
        private Component emptyText = Component.literal("无可用项目");
        private BooleanSupplier itemEnabled = ALWAYS_ENABLED;
        private BooleanSupplier itemVisible = ALWAYS_VISIBLE;

        private ListGroup(String id, Component title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public ListTemplate<T> items(Supplier<List<T>> items) {
            this.items = items;
            return this;
        }

        @Override
        public ListTemplate<T> label(Function<T, Component> labeler) {
            this.labeler = labeler;
            return this;
        }

        @Override
        public ListTemplate<T> selected(Supplier<T> selected) {
            this.selected = selected;
            return this;
        }

        @Override
        public ListTemplate<T> onSelect(Consumer<T> onSelect) {
            this.onSelect = onSelect;
            return this;
        }

        @Override
        public ListTemplate<T> itemActions(BiConsumer<T, ItemActionTemplate<T>> builder) {
            this.itemActionsBuilder = builder == null ? (item, actions) -> {} : builder;
            return this;
        }

        @Override
        public ListTemplate<T> emptyText(Component emptyText) {
            this.emptyText = emptyText;
            return this;
        }

        @Override
        public ListTemplate<T> enabled(BooleanSupplier enabled) {
            this.itemEnabled = enabled;
            return this;
        }

        @Override
        public ListTemplate<T> visible(BooleanSupplier visible) {
            this.itemVisible = visible;
            return this;
        }

        private SettingsTemplateModel.ListGroup<T> toModel(BooleanSupplier enabled, BooleanSupplier visible) {
            return new SettingsTemplateModel.ListGroup<>(id, title, items, labeler, selected, onSelect, this::buildItemActions, emptyText, () -> enabled.getAsBoolean() && itemEnabled.getAsBoolean(), () -> visible.getAsBoolean() && itemVisible.getAsBoolean());
        }

        private List<SettingsTemplateModel.ItemActionEntry<T>> buildItemActions(T item) {
            ItemActionGroup<T> group = new ItemActionGroup<>();
            itemActionsBuilder.accept(item, group);
            return group.entries();
        }
    }

    private static final class ItemActionGroup<T> implements ItemActionTemplate<T> {
        private final List<SettingsTemplateModel.ItemActionEntry<T>> entries = new ArrayList<>();

        @Override
        public ItemActionTemplate<T> button(String id, Component label, Consumer<T> action) {
            return button(id, label, SettingsButtonStyle.NORMAL, action, itemPredicate(ALWAYS_ITEM_ENABLED), itemPredicate(ALWAYS_ITEM_VISIBLE));
        }

        @Override
        public ItemActionTemplate<T> button(String id, Component label, Consumer<T> action, Predicate<T> enabled) {
            return button(id, label, SettingsButtonStyle.NORMAL, action, enabled, itemPredicate(ALWAYS_ITEM_VISIBLE));
        }

        @Override
        public ItemActionTemplate<T> button(String id, Component label, Consumer<T> action, Predicate<T> enabled, Predicate<T> visible) {
            return button(id, label, SettingsButtonStyle.NORMAL, action, enabled, visible);
        }

        @Override
        public ItemActionTemplate<T> button(String id, Component label, SettingsButtonStyle style, Consumer<T> action) {
            return button(id, label, style, action, itemPredicate(ALWAYS_ITEM_ENABLED), itemPredicate(ALWAYS_ITEM_VISIBLE));
        }

        @Override
        public ItemActionTemplate<T> button(String id, Component label, SettingsButtonStyle style, Consumer<T> action, Predicate<T> enabled) {
            return button(id, label, style, action, enabled, itemPredicate(ALWAYS_ITEM_VISIBLE));
        }

        @Override
        public ItemActionTemplate<T> button(String id, Component label, SettingsButtonStyle style, Consumer<T> action, Predicate<T> enabled, Predicate<T> visible) {
            entries.add(new SettingsTemplateModel.ItemActionEntry<>(id, label, style, action, enabled, visible));
            return this;
        }

        List<SettingsTemplateModel.ItemActionEntry<T>> entries() {
            return List.copyOf(entries);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Predicate<T> itemPredicate(Predicate<Object> predicate) {
        return (Predicate<T>) predicate;
    }
}
