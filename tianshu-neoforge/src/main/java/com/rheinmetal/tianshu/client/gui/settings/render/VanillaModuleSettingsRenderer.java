package com.rheinmetal.tianshu.client.gui.settings.render;

import com.rheinmetal.tianshu.client.gui.settings.api.SettingsListCard;
import com.rheinmetal.tianshu.client.gui.settings.api.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.gui.settings.layout.SettingsLayout;
import com.rheinmetal.tianshu.client.gui.settings.layout.SettingsLayoutItem;
import com.rheinmetal.tianshu.client.gui.settings.layout.SettingsLayoutMetrics;
import com.rheinmetal.tianshu.client.gui.settings.layout.SettingsViewport;
import com.rheinmetal.tianshu.client.gui.settings.model.SettingsTemplateModel;
import com.rheinmetal.tianshu.client.gui.settings.screen.TianshuSettingsScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class VanillaModuleSettingsRenderer implements ModuleSettingsRenderer {
    private static final int ITEM_ACTION_WIDTH = 54;
    private static final int SECTION_BACKGROUND = 0x22000000;
    private static final int SECTION_BORDER_LIGHT = 0x33808080;
    private static final int SECTION_BORDER_DARK = 0x66000000;
    private static final int SECTION_DISABLED_BORDER_LIGHT = 0x22808080;
    private static final int SECTION_DISABLED_BORDER_DARK = 0x44000000;
    private TianshuSettingsScreen screen;
    private Font font;
    private SettingsLayout layout;
    private SettingsViewport viewport;
    private int x;
    private int width;
    private int gridColumn;
    private int gridColumnsOverride;
    private final List<SettingsDecoration> decorations = new ArrayList<>();

    @Override
    public SettingsRenderResult render(TianshuSettingsScreen screen, Font font, int x, int y, int width, SettingsViewport viewport, List<SettingsTemplateModel> templates) {
        this.screen = screen;
        this.font = font;
        this.layout = new SettingsLayout(y, viewport);
        this.viewport = viewport;
        this.x = x;
        this.width = width;
        this.gridColumn = 0;
        this.decorations.clear();
        for (SettingsTemplateModel template : safeList(templates)) {
            if (template != null) {
                renderTemplate(template);
            }
        }
        return new SettingsRenderResult(layout.contentHeight(), decorations);
    }

    private void renderTemplate(SettingsTemplateModel template) {
        if (!safeBoolean(template.visible(), true)) {
            return;
        }
        switch (template) {
            case SettingsTemplateModel.Enable enable -> renderEnable(enable);
            case SettingsTemplateModel.ToggleGroup group -> renderToggleGroup(group);
            case SettingsTemplateModel.OptionGroup group -> renderOptionGroup(group);
            case SettingsTemplateModel.StatusGroup group -> renderStatusGroup(group);
            case SettingsTemplateModel.ActionGroup group -> renderActionGroup(group);
            case SettingsTemplateModel.ListGroup<?> group -> renderListGroup(group);
            case SettingsTemplateModel.CatalogGroup<?> group -> renderCatalogGroup(group);
            case SettingsTemplateModel.Columns columns -> renderColumns(columns);
            case SettingsTemplateModel.TextBlock text -> renderTextBlock(text);
            case SettingsTemplateModel.Separator ignored -> layout.groupGap();
        }
    }

    private void renderEnable(SettingsTemplateModel.Enable enable) {
        boolean active = safeBoolean(enable.enabled(), true);
        renderSection(active, () -> {
            SettingsLayoutItem item = nextGridItem();
            addIfVisible(CycleButton.onOffBuilder(safeBoolean(enable.getter(), false))
                    .create(itemX(item), item.screenY(), itemWidth(item), SettingsLayoutMetrics.CONTROL_HEIGHT, safeComponent(enable.label(), Component.empty()), (button, selected) -> enable.setter().accept(selected)), item, active);
        });
    }

    private void renderToggleGroup(SettingsTemplateModel.ToggleGroup group) {
        boolean groupActive = safeBoolean(group.enabled(), true);
        renderSection(groupActive, () -> {
            renderGroupTitle(group.title(), groupActive);
            for (SettingsTemplateModel.ToggleEntry entry : safeList(group.entries())) {
                if (entry == null || !safeBoolean(entry.visible(), true)) {
                    continue;
                }
                SettingsLayoutItem item = nextGridItem();
                addIfVisible(CycleButton.onOffBuilder(safeBoolean(entry.getter(), false))
                        .create(itemX(item), item.screenY(), itemWidth(item), SettingsLayoutMetrics.CONTROL_HEIGHT, safeComponent(entry.label(), Component.empty()), (button, selected) -> entry.setter().accept(selected)), item, groupActive && safeBoolean(entry.enabled(), true));
            }
        });
    }

    private void renderOptionGroup(SettingsTemplateModel.OptionGroup group) {
        boolean groupActive = safeBoolean(group.enabled(), true);
        renderSection(groupActive, () -> {
            renderGroupTitle(group.title(), groupActive);
            renderOptionEntries(group.entries(), groupActive, false);
        });
    }

    private void renderOptionEntries(List<SettingsTemplateModel.OptionEntry> entries, boolean groupActive, boolean compact) {
        int previousOverride = gridColumnsOverride;
        gridColumnsOverride = compact ? compactGridColumns(entries) : 0;
        try {
            for (SettingsTemplateModel.OptionEntry entry : safeList(entries)) {
                if (entry == null || !safeBoolean(entry.visible(), true)) {
                    continue;
                }
                boolean active = groupActive && safeBoolean(entry.enabled(), true);
                switch (entry) {
                    case SettingsTemplateModel.SelectEntry<?> select -> renderSelect(select, active);
                    case SettingsTemplateModel.TextEntry text -> renderTextEntry(text, active);
                    case SettingsTemplateModel.SliderEntry slider -> renderSlider(slider, active);
                }
            }
            finishGridRow();
        } finally {
            gridColumnsOverride = previousOverride;
        }
    }

    private <T> void renderSelect(SettingsTemplateModel.SelectEntry<T> select, boolean active) {
        SettingsLayoutItem item = nextGridItem();
        List<T> values = safeList(select.values());
        if (values.isEmpty()) {
            addIfVisible(new SettingsTextWidget(itemX(item), item.screenY(), itemWidth(item), SettingsLayoutMetrics.ROW_HEIGHT, Component.translatable("tianshu.gui.settings.option.no_available", safeComponent(select.label(), Component.empty())), active ? 0xA0A0A0 : 0x606060), item, false);
            return;
        }
        T selected = safeGet(select.getter(), values.get(0));
        if (!values.contains(selected)) {
            selected = values.get(0);
        }
        final T currentValue = selected;
        Component selectedLabel = safeLabel(select.labeler(), currentValue);
        Component buttonLabel = Component.translatable("tianshu.gui.settings.option.selected", safeComponent(select.label(), Component.empty()), selectedLabel);
        Button button = Button.builder(buttonLabel, clicked -> openSelectionOverlay(select, currentValue))
                .pos(itemX(item), item.screenY())
                .size(itemWidth(item), SettingsLayoutMetrics.CONTROL_HEIGHT)
                .build();
        addIfVisible(button, item, active);
    }

    private <T> void openSelectionOverlay(SettingsTemplateModel.SelectEntry<T> select, T current) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen currentScreen = minecraft.screen;
        minecraft.setScreen(new SettingsSelectionOverlay<>(currentScreen, safeComponent(select.label(), Component.empty()), safeList(select.values()), current, select.labeler(), value -> {
            select.setter().accept(value);
            if (currentScreen instanceof TianshuSettingsScreen settingsScreen) {
                settingsScreen.rebuildCurrentPage();
            }
        }));
    }

    private void renderTextEntry(SettingsTemplateModel.TextEntry text, boolean active) {
        finishGridRow();
        SettingsLayoutItem label = layout.next(12);
        Component labelComponent = safeComponent(text.label(), Component.empty());
        addIfVisible(new SettingsTextWidget(contentX(), label.screenY(), contentWidth(), SettingsLayoutMetrics.LABEL_HEIGHT, labelComponent, active ? 0xA0A0A0 : 0x606060), label, active);
        SettingsLayoutItem item = layout.row();
        EditBox box = new EditBox(font, contentX(), item.screenY(), Math.min(360, contentWidth()), SettingsLayoutMetrics.CONTROL_HEIGHT, labelComponent);
        box.setValue(safeGet(text.getter(), ""));
        box.setResponder(value -> text.setter().accept(value));
        addIfVisible(box, item, active);
        layout.gap();
    }

    private void renderSlider(SettingsTemplateModel.SliderEntry slider, boolean active) {
        SettingsLayoutItem item = nextGridItem();
        double value = safeGet(slider.getter(), slider.min());
        addIfVisible(new SettingsSlider(itemX(item), item.screenY(), itemWidth(item), SettingsLayoutMetrics.CONTROL_HEIGHT, safeComponent(slider.label(), Component.empty()), value, slider.min(), slider.max(), slider.setter()), item, active);
    }

    private void renderStatusGroup(SettingsTemplateModel.StatusGroup group) {
        boolean groupActive = safeBoolean(group.enabled(), true);
        renderSection(groupActive, () -> {
            renderGroupTitle(group.title(), groupActive);
            for (SettingsTemplateModel.StatusEntry entry : safeList(group.entries())) {
                if (entry == null || !safeBoolean(entry.visible(), true)) {
                    continue;
                }
                boolean active = groupActive && safeBoolean(entry.enabled(), true);
                SettingsLayoutItem item = nextGridItem();
                addIfVisible(new SettingsTextWidget(itemX(item), item.screenY(), itemWidth(item), SettingsLayoutMetrics.ROW_HEIGHT, Component.translatable("tianshu.gui.settings.status.row", safeComponent(entry.label(), Component.empty()), safeGet(entry.value(), Component.empty())), active ? 0xD0D0D0 : 0x606060), item, active);
            }
        });
    }

    private void renderActionGroup(SettingsTemplateModel.ActionGroup group) {
        boolean groupActive = safeBoolean(group.enabled(), true);
        renderSection(groupActive, () -> {
            renderGroupTitle(group.title(), groupActive);
            int buttonX = contentX();
            SettingsLayoutItem item = layout.row();
            int buttonWidth = 96;
            boolean hasVisibleEntry = false;
            for (SettingsTemplateModel.ActionEntry entry : safeList(group.entries())) {
                if (entry == null || !safeBoolean(entry.visible(), true)) {
                    continue;
                }
                hasVisibleEntry = true;
                if (buttonX + buttonWidth > contentX() + contentWidth()) {
                    item = layout.row();
                    buttonX = contentX();
                }
                Button button = Button.builder(styledButtonLabel(entry), clicked -> runActionAndRefresh(entry.action()))
                        .pos(buttonX, item.screenY())
                        .size(buttonWidth, SettingsLayoutMetrics.CONTROL_HEIGHT)
                        .build();
                addIfVisible(button, item, groupActive && safeBoolean(entry.enabled(), true));
                buttonX += buttonWidth + SettingsLayoutMetrics.GAP;
            }
            if (!hasVisibleEntry) {
                layout.gap();
            } else {
                layout.gap();
            }
        });
    }

    private <T> void renderListGroup(SettingsTemplateModel.ListGroup<T> group) {
        boolean groupActive = safeBoolean(group.enabled(), true);
        renderSection(groupActive, () -> {
            renderGroupTitle(group.title(), groupActive);
            renderListItems(group, groupActive);
        });
    }

    private <T> void renderCatalogGroup(SettingsTemplateModel.CatalogGroup<T> group) {
        boolean groupActive = safeBoolean(group.enabled(), true);
        renderSection(groupActive, () -> {
            renderGroupTitle(group.title(), groupActive);
            renderOptionEntries(group.controls(), groupActive, true);
            renderListItems(group.list(), groupActive);
        });
    }

    private <T> void renderListItems(SettingsTemplateModel.ListGroup<T> group, boolean groupActive) {
        List<T> items = safeList(safeGet(group.items(), List.of()));
        if (items.isEmpty()) {
            SettingsLayoutItem item = layout.row();
            addIfVisible(new SettingsTextWidget(contentX(), item.screenY(), contentWidth(), SettingsLayoutMetrics.ROW_HEIGHT, safeComponent(group.emptyText(), Component.translatable("tianshu.gui.common.no_available_items")), groupActive ? 0xA0A0A0 : 0x606060), item, groupActive);
            return;
        }
        T selected = safeGet(group.selected(), null);
        for (T value : items) {
            boolean active = value == selected || value != null && value.equals(selected);
            List<SettingsTemplateModel.ItemActionEntry<T>> actions = visibleItemActions(group, value);
            int rowX = contentX();
            int rowWidth = contentWidth();
            int actionWidth = actions.isEmpty() ? 0 : actions.size() * ITEM_ACTION_WIDTH + Math.max(0, actions.size() - 1) * SettingsLayoutMetrics.GAP;
            int bodyWidth = Math.max(80, rowWidth - actionWidth - (actions.isEmpty() ? 0 : SettingsLayoutMetrics.GAP));
            SettingsLayoutItem item;
            AbstractWidget listWidget;
            if (group.carder() != null) {
                SettingsListCard card = safeApply(group.carder(), value, SettingsListCard.text(Component.empty()));
                int cardHeight = SettingsListCardWidget.heightFor(font, card, bodyWidth);
                item = layout.next(cardHeight);
                listWidget = new SettingsListCardWidget(rowX, item.screenY(), bodyWidth, cardHeight, card, () -> runActionAndRefresh(() -> safeAccept(group.onSelect(), value)));
            } else {
                Component valueLabel = safeLabel(group.labeler(), value);
                Component label = active ? Component.translatable("tianshu.gui.settings.list.selected", valueLabel) : valueLabel;
                int itemHeight = Math.max(SettingsLayoutMetrics.CONTROL_HEIGHT, SettingsListItemWidget.heightFor(font, label, bodyWidth));
                item = layout.next(itemHeight);
                listWidget = new SettingsListItemWidget(rowX, item.screenY(), bodyWidth, itemHeight, label, groupActive ? 0xD0D0D0 : 0x606060, () -> runActionAndRefresh(() -> safeAccept(group.onSelect(), value)));
            }
            addIfVisible(listWidget, item, groupActive);
            int buttonX = rowX + bodyWidth + SettingsLayoutMetrics.GAP;
            int buttonY = item.screenY() + Math.max(0, (item.height() - SettingsLayoutMetrics.CONTROL_HEIGHT) / 2);
            for (SettingsTemplateModel.ItemActionEntry<T> action : actions) {
                Button button = Button.builder(styledItemButtonLabel(action), clicked -> runActionAndRefresh(() -> safeAccept(action.action(), value)))
                        .pos(buttonX, buttonY)
                        .size(ITEM_ACTION_WIDTH, SettingsLayoutMetrics.CONTROL_HEIGHT)
                        .build();
                addIfVisible(button, item, groupActive);
                buttonX += ITEM_ACTION_WIDTH + SettingsLayoutMetrics.GAP;
            }
            layout.gap();
        }
    }

    private void renderTextBlock(SettingsTemplateModel.TextBlock text) {
        boolean active = safeBoolean(text.enabled(), true);
        int color = switch (text.level()) {
            case INFO -> active ? 0xD0D0D0 : 0x606060;
            case WARNING -> active ? 0xFFFF66 : 0x606060;
            case ERROR -> active ? 0xFF6666 : 0x606060;
        };
        renderSection(active, () -> {
            finishGridRow();
            SettingsLayoutItem item = layout.row();
            addIfVisible(new SettingsTextWidget(contentX(), item.screenY(), contentWidth(), SettingsLayoutMetrics.ROW_HEIGHT, safeComponent(text.text(), Component.empty()), color), item, active);
        });
    }

    private void renderColumns(SettingsTemplateModel.Columns columns) {
        if (!safeBoolean(columns.enabled(), true)) {
            return;
        }
        List<Double> weights = safeList(columns.weights());
        List<List<SettingsTemplateModel>> columnTemplates = safeList(columns.columns());
        int count = Math.min(weights.size(), columnTemplates.size());
        if (count <= 0) {
            return;
        }
        finishGridRow();
        int startY = layout.cursorY();
        int gap = SettingsLayoutMetrics.GROUP_GAP;
        int availableWidth = Math.max(1, width - gap * (count - 1));
        double totalWeight = weights.stream().limit(count).mapToDouble(value -> value == null || value <= 0.0D ? 1.0D : value).sum();
        if (totalWeight <= 0.0D) {
            totalWeight = count;
        }

        RendererState state = saveState();
        int columnX = x;
        int remainingWidth = availableWidth;
        double remainingWeight = totalWeight;
        int maxEndY = startY;
        for (int i = 0; i < count; i++) {
            double weight = weights.get(i) == null || weights.get(i) <= 0.0D ? 1.0D : weights.get(i);
            int columnWidth = i == count - 1 ? remainingWidth : Math.max(1, (int) Math.round(remainingWidth * (weight / remainingWeight)));
            restoreForColumn(state, columnX, columnWidth, startY);
            for (SettingsTemplateModel template : safeList(columnTemplates.get(i))) {
                if (template != null) {
                    renderTemplate(template);
                }
            }
            maxEndY = Math.max(maxEndY, layout.cursorY());
            columnX += columnWidth + gap;
            remainingWidth = Math.max(1, remainingWidth - columnWidth);
            remainingWeight = Math.max(1.0D, remainingWeight - weight);
        }
        restoreState(state);
        layout.advanceTo(maxEndY);
        layout.groupGap();
    }

    private void renderGroupTitle(Component title, boolean active) {
        finishGridRow();
        SettingsLayoutItem item = layout.row();
        addIfVisible(new SettingsTextWidget(contentX(), item.screenY(), contentWidth(), SettingsLayoutMetrics.ROW_HEIGHT, safeComponent(title, Component.empty()), active ? 0xFFFFFF : 0x707070), item, active);
    }

    private void renderSection(boolean active, Runnable content) {
        gridColumn = 0;
        int startY = layout.cursorY();
        layout.sectionPadding();
        content.run();
        finishGridRow();
        layout.sectionPadding();
        int endY = layout.cursorY();
        int screenY = viewport.translateY(startY);
        decorations.add(new SettingsDecoration(
                x,
                screenY,
                width,
                Math.max(1, endY - startY),
                SECTION_BACKGROUND,
                active ? SECTION_BORDER_LIGHT : SECTION_DISABLED_BORDER_LIGHT,
                active ? SECTION_BORDER_DARK : SECTION_DISABLED_BORDER_DARK
        ));
        layout.groupGap();
    }

    private int contentX() {
        return x + SettingsLayoutMetrics.SECTION_PADDING;
    }

    private int contentWidth() {
        return Math.max(1, width - SettingsLayoutMetrics.SECTION_PADDING * 2);
    }

    private SettingsLayoutItem nextGridItem() {
        int columns = gridColumns();
        int cellWidth = gridCellWidth(columns);
        SettingsLayoutItem item = gridColumn == 0 ? layout.row() : layout.peekLastRow();
        SettingsLayoutItem positioned = new SettingsLayoutItem(item.contentY(), item.screenY(), item.height(), item.visible(), gridColumn, cellWidth);
        gridColumn++;
        if (gridColumn >= columns) {
            gridColumn = 0;
            layout.gap();
        }
        return positioned;
    }

    private void finishGridRow() {
        if (gridColumn != 0) {
            gridColumn = 0;
            layout.gap();
        }
    }

    private int gridColumns() {
        if (gridColumnsOverride > 0) {
            return gridColumnsOverride;
        }
        return contentWidth() >= 330 ? 2 : 1;
    }

    private int compactGridColumns(List<SettingsTemplateModel.OptionEntry> entries) {
        int visible = 0;
        for (SettingsTemplateModel.OptionEntry entry : safeList(entries)) {
            if (entry != null && safeBoolean(entry.visible(), true)) {
                visible++;
            }
        }
        if (visible <= 0) {
            return 1;
        }
        int maxByWidth = Math.max(1, contentWidth() / 64);
        return Math.max(1, Math.min(visible, Math.min(5, maxByWidth)));
    }

    private int gridCellWidth(int columns) {
        return columns <= 1 ? contentWidth() : Math.max(1, (contentWidth() - SettingsLayoutMetrics.GAP * (columns - 1)) / columns);
    }

    private int itemX(SettingsLayoutItem item) {
        return contentX() + item.column() * (item.width() + SettingsLayoutMetrics.GAP);
    }

    private int itemWidth(SettingsLayoutItem item) {
        return Math.max(1, item.width());
    }

    private RendererState saveState() {
        return new RendererState(layout, x, width, gridColumn);
    }

    private void restoreForColumn(RendererState state, int columnX, int columnWidth, int startY) {
        this.layout = new SettingsLayout(startY, viewport);
        this.x = columnX;
        this.width = columnWidth;
        this.gridColumn = 0;
    }

    private void restoreState(RendererState state) {
        this.layout = state.layout();
        this.x = state.x();
        this.width = state.width();
        this.gridColumn = state.gridColumn();
    }

    private record RendererState(SettingsLayout layout, int x, int width, int gridColumn) {}

    private Component styledButtonLabel(SettingsTemplateModel.ActionEntry entry) {
        Component label = safeComponent(entry.label(), Component.empty());
        SettingsButtonStyle style = entry.style() == null ? SettingsButtonStyle.NORMAL : entry.style();
        return styledLabel(label, style);
    }

    private <T> Component styledItemButtonLabel(SettingsTemplateModel.ItemActionEntry<T> entry) {
        Component label = safeComponent(entry.label(), Component.empty());
        SettingsButtonStyle style = entry.style() == null ? SettingsButtonStyle.NORMAL : entry.style();
        return styledLabel(label, style);
    }

    private Component styledLabel(Component label, SettingsButtonStyle style) {
        return switch (style) {
            case NORMAL -> label;
            case PRIMARY -> Component.translatable("tianshu.gui.settings.button.primary", label);
            case DANGER -> Component.translatable("tianshu.gui.settings.button.danger", label);
        };
    }

    private <T> List<SettingsTemplateModel.ItemActionEntry<T>> visibleItemActions(SettingsTemplateModel.ListGroup<T> group, T item) {
        List<SettingsTemplateModel.ItemActionEntry<T>> entries = safeList(safeApply(group.itemActions(), item, List.of()));
        return entries.stream()
                .filter(entry -> entry != null && safeTest(entry.visible(), item, true) && safeTest(entry.enabled(), item, true))
                .toList();
    }

    private <T> Component safeLabel(Function<T, Component> function, T value) {
        try {
            Component label = function == null ? null : function.apply(value);
            return label == null ? Component.literal(String.valueOf(value)) : label;
        } catch (RuntimeException ignored) {
            return Component.literal(String.valueOf(value));
        }
    }

    private void safeRun(Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    private void runActionAndRefresh(Runnable action) {
        try {
            safeRun(action);
        } catch (RuntimeException exception) {
            if (screen != null) {
                screen.showActionFailure(exception);
            }
        } finally {
            if (screen != null) {
                screen.rebuildCurrentPage();
            }
        }
    }

    private <T> void safeAccept(Consumer<T> action, T value) {
        if (action != null) {
            action.accept(value);
        }
    }

    private <T> boolean safeTest(Predicate<T> predicate, T value, boolean fallback) {
        try {
            return predicate == null ? fallback : predicate.test(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private <T, R> R safeApply(Function<T, R> function, T value, R fallback) {
        try {
            R result = function == null ? fallback : function.apply(value);
            return result == null ? fallback : result;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private boolean safeBoolean(BooleanSupplier supplier, boolean fallback) {
        try {
            return supplier == null ? fallback : supplier.getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private <T> T safeGet(Supplier<T> supplier, T fallback) {
        try {
            T value = supplier == null ? fallback : supplier.get();
            return value == null ? fallback : value;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private Component safeComponent(Component value, Component fallback) {
        return value == null ? fallback : value;
    }

    private void addIfVisible(AbstractWidget widget, SettingsLayoutItem item, boolean active) {
        if (item.visible()) {
            widget.active = active;
            screen.addSettingsWidget(widget);
        }
    }
}
