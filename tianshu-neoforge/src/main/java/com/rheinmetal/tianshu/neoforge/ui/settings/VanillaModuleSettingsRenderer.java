package com.rheinmetal.tianshu.neoforge.ui.settings;

import com.rheinmetal.tianshu.client.api.settings.SettingsListCard;
import com.rheinmetal.tianshu.client.api.settings.SettingsButtonStyle;
import com.rheinmetal.tianshu.neoforge.ui.settings.NeoForgeUiText;
import com.rheinmetal.tianshu.client.settings.layout.SettingsLayout;
import com.rheinmetal.tianshu.client.settings.layout.SettingsLayoutItem;
import com.rheinmetal.tianshu.client.settings.layout.SettingsLayoutMetrics;
import com.rheinmetal.tianshu.client.settings.layout.SettingsViewport;
import com.rheinmetal.tianshu.client.settings.model.SettingsTemplateModel;
import com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsScreen;
import com.rheinmetal.tianshu.client.api.text.UiText;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class VanillaModuleSettingsRenderer implements ModuleSettingsRenderer {
    private static final int ITEM_ACTION_WIDTH = 54;
    private static final int ROW_LABEL_MIN_WIDTH = 88;
    private static final ControlSizing SWITCH_CONTROL = new ControlSizing(48, 56, 60);
    private static final ControlSizing SELECT_CONTROL = new ControlSizing(72, 96, 112);
    private static final ControlSizing SLIDER_CONTROL = new ControlSizing(88, 112, 128);
    private static final int COMPACT_OPTION_MIN_WIDTH = 58;
    private static final int COMPACT_OPTION_MAX_WIDTH = 92;
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
    private int columnBottomTargetY = -1;
    private final List<SettingsDecoration> decorations = new ArrayList<>();
    private final List<SettingsScrollRegion> scrollRegions = new ArrayList<>();
    private List<AbstractWidget> activeScrollRegionWidgets;

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
        this.scrollRegions.clear();
        for (SettingsTemplateModel template : safeList(templates)) {
            if (template != null) {
                renderTemplate(template);
            }
        }
        return new SettingsRenderResult(layout.contentHeight(), decorations, scrollRegions);
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
            case SettingsTemplateModel.CompoundGroup group -> renderCompoundGroup(group);
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
                renderControlRow(safeComponent(enable.label(), Component.empty()), active, SWITCH_CONTROL, item -> {
                    CycleButton<Boolean> button = booleanToggleBuilder(safeBoolean(enable.getter(), false))
                        .create(item.controlX(), item.screenY(), item.controlWidth(), SettingsLayoutMetrics.CONTROL_HEIGHT, Component.empty(), (btn, selected) -> enable.setter().accept(selected));
                    addIfVisible(button, item.row(), active);
                });
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
                renderControlRow(safeComponent(entry.label(), Component.empty()), groupActive && safeBoolean(entry.enabled(), true), SWITCH_CONTROL, item -> {
                    CycleButton<Boolean> button = booleanToggleBuilder(safeBoolean(entry.getter(), false))
                            .create(item.controlX(), item.screenY(), item.controlWidth(), SettingsLayoutMetrics.CONTROL_HEIGHT, Component.empty(), (btn, selected) -> entry.setter().accept(selected));
                    addIfVisible(button, item.row(), groupActive && safeBoolean(entry.enabled(), true));
                });
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
        if (compact) {
            renderCompactOptionEntries(entries, groupActive);
            return;
        }
        int previousOverride = gridColumnsOverride;
        gridColumnsOverride = 0;
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

    private void renderCompactOptionEntries(List<SettingsTemplateModel.OptionEntry> entries, boolean groupActive) {
        finishGridRow();
        List<SettingsTemplateModel.OptionEntry> visibleEntries = safeList(entries).stream()
                .filter(entry -> entry != null && safeBoolean(entry.visible(), true))
                .toList();
        if (visibleEntries.isEmpty()) {
            return;
        }
        int buttonX = contentX();
        SettingsLayoutItem item = layout.row();
        int visibleCount = visibleEntries.size();
        int buttonWidth = Math.max(1, (contentWidth() - SettingsLayoutMetrics.GAP * Math.max(0, visibleCount - 1)) / visibleCount);
        for (SettingsTemplateModel.OptionEntry entry : visibleEntries) {
            boolean active = groupActive && safeBoolean(entry.enabled(), true);
            switch (entry) {
                case SettingsTemplateModel.SelectEntry<?> select -> renderCompactSelect(select, active, item, buttonX, buttonWidth);
                case SettingsTemplateModel.TextEntry text -> renderCompactTextEntry(text, active, item, buttonX, buttonWidth);
                case SettingsTemplateModel.SliderEntry slider -> renderCompactSlider(slider, active, item, buttonX, buttonWidth);
            }
            buttonX += buttonWidth + SettingsLayoutMetrics.GAP;
        }
        layout.gap();
    }

    private <T> void renderSelect(SettingsTemplateModel.SelectEntry<T> select, boolean active) {
        List<T> values = safeList(select.values());
        if (values.isEmpty()) {
            SettingsLayoutItem item = nextGridItem();
            addIfVisible(new SettingsTextWidget(itemX(item), item.screenY(), itemWidth(item), SettingsLayoutMetrics.ROW_HEIGHT, Component.translatable("tianshu.gui.settings.option.no_available", safeComponent(select.label(), Component.empty())), active ? 0xA0A0A0 : 0x606060), item, false);
            return;
        }
        T selected = safeGet(select.getter(), values.get(0));
        if (!values.contains(selected)) {
            selected = values.get(0);
        }
        final T currentValue = selected;
        Component selectedLabel = safeLabel(select.labeler(), currentValue);
        renderControlRow(safeComponent(select.label(), Component.empty()), active, SELECT_CONTROL, item -> {
            Component buttonLabel = selectedLabel;
            Button button = Button.builder(buttonLabel, clicked -> {
                        if (values.size() == 2) {
                            T nextValue = values.get(Objects.equals(values.get(0), currentValue) ? 1 : 0);
                            select.setter().accept(nextValue);
                            if (screen != null) {
                                screen.requestRebuildCurrentPage();
                            }
                        } else {
                            openSelectionPanel(select, currentValue);
                        }
                    })
                    .pos(item.controlX(), item.screenY())
                    .size(item.controlWidth(), SettingsLayoutMetrics.CONTROL_HEIGHT)
                    .build();
            addIfVisible(button, item.row(), active);
        });
    }

    private <T> void renderCompactSelect(SettingsTemplateModel.SelectEntry<T> select, boolean active, SettingsLayoutItem item, int buttonX, int buttonWidth) {
        List<T> values = safeList(select.values());
        if (values.isEmpty()) {
            addIfVisible(new SettingsTextWidget(buttonX, item.screenY(), buttonWidth, SettingsLayoutMetrics.ROW_HEIGHT, Component.translatable("tianshu.gui.settings.option.no_available", safeComponent(select.label(), Component.empty())), active ? 0xA0A0A0 : 0x606060), item, false);
            return;
        }
        T selected = safeGet(select.getter(), values.get(0));
        if (!values.contains(selected)) {
            selected = values.get(0);
        }
        final T currentValue = selected;
        Component selectedLabel = safeLabel(select.labeler(), currentValue);
        Button button = Button.builder(Component.translatable("tianshu.gui.settings.option.selected", safeComponent(select.label(), Component.empty()), selectedLabel), clicked -> {
                    if (values.size() == 2) {
                        T nextValue = values.get(Objects.equals(values.get(0), currentValue) ? 1 : 0);
                        select.setter().accept(nextValue);
                        if (screen != null) {
                            screen.requestRebuildCurrentPage();
                        }
                    } else {
                        openSelectionPanel(select, currentValue);
                    }
                })
                .pos(buttonX, item.screenY())
                .size(buttonWidth, SettingsLayoutMetrics.CONTROL_HEIGHT)
                .build();
        addIfVisible(button, item, active);
    }

    private <T> void openSelectionPanel(SettingsTemplateModel.SelectEntry<T> select, T current) {
        if (screen != null) {
            screen.openSelectionPanel(select.label(), safeList(select.values()), current, select.labeler(), select.setter());
        }
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

    private void renderCompactTextEntry(SettingsTemplateModel.TextEntry text, boolean active, SettingsLayoutItem item, int buttonX, int buttonWidth) {
        Component labelComponent = safeComponent(text.label(), Component.empty());
        EditBox box = new EditBox(font, buttonX, item.screenY(), buttonWidth, SettingsLayoutMetrics.CONTROL_HEIGHT, labelComponent);
        box.setValue(safeGet(text.getter(), ""));
        box.setResponder(value -> text.setter().accept(value));
        addIfVisible(box, item, active);
    }

    private void renderSlider(SettingsTemplateModel.SliderEntry slider, boolean active) {
        double value = safeGet(slider.getter(), slider.min());
        renderControlRow(safeComponent(slider.label(), Component.empty()), active, SLIDER_CONTROL, item ->
                addIfVisible(new SettingsSlider(item.controlX(), item.screenY(), item.controlWidth(), SettingsLayoutMetrics.CONTROL_HEIGHT, Component.empty(), value, slider.min(), slider.max(), slider.setter(), false), item.row(), active));
    }

    private void renderCompactSlider(SettingsTemplateModel.SliderEntry slider, boolean active, SettingsLayoutItem item, int buttonX, int buttonWidth) {
        double value = safeGet(slider.getter(), slider.min());
        addIfVisible(new SettingsSlider(buttonX, item.screenY(), buttonWidth, SettingsLayoutMetrics.CONTROL_HEIGHT, safeComponent(slider.label(), Component.empty()), value, slider.min(), slider.max(), slider.setter()), item, active);
    }

    private void renderStatusGroup(SettingsTemplateModel.StatusGroup group) {
        boolean groupActive = safeBoolean(group.enabled(), true);
        renderSection(groupActive, () -> {
            renderGroupTitle(group.title(), groupActive);
            renderStatusEntries(group.entries(), groupActive);
        });
    }

    private void renderActionGroup(SettingsTemplateModel.ActionGroup group) {
        boolean groupActive = safeBoolean(group.enabled(), true);
        renderSection(groupActive, () -> {
            renderGroupTitle(group.title(), groupActive);
            renderActionEntries(group.entries(), groupActive);
        });
    }

    private void renderCompoundGroup(SettingsTemplateModel.CompoundGroup group) {
        boolean groupActive = safeBoolean(group.enabled(), true);
        renderSection(groupActive, () -> {
            renderGroupTitle(group.title(), groupActive);
            renderOptionEntries(group.options(), groupActive, false);
            renderActionEntries(group.actions(), groupActive);
            renderStatusEntries(group.statuses(), groupActive);
        });
    }

    private void renderActionEntries(List<SettingsTemplateModel.ActionEntry> entries, boolean groupActive) {
        int buttonX = contentX();
        SettingsLayoutItem item = layout.row();
        int buttonWidth = 96;
        boolean hasVisibleEntry = false;
        for (SettingsTemplateModel.ActionEntry entry : safeList(entries)) {
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
        if (hasVisibleEntry) {
            layout.gap();
        }
    }

    private void renderStatusEntries(List<SettingsTemplateModel.StatusEntry> entries, boolean groupActive) {
        for (SettingsTemplateModel.StatusEntry entry : safeList(entries)) {
            if (entry == null || !safeBoolean(entry.visible(), true)) {
                continue;
            }
            boolean active = groupActive && safeBoolean(entry.enabled(), true);
            SettingsLayoutItem item = nextGridItem();
            addIfVisible(new SettingsTextWidget(itemX(item), item.screenY(), itemWidth(item), SettingsLayoutMetrics.ROW_HEIGHT, Component.translatable("tianshu.gui.settings.status.row", safeComponent(entry.label(), Component.empty()), safeComponent(safeGet(entry.value(), UiText.literal("")), Component.empty())), active ? 0xD0D0D0 : 0x606060), item, active);
        }
    }

    private <T> void renderListGroup(SettingsTemplateModel.ListGroup<T> group) {
        boolean groupActive = safeBoolean(group.enabled(), true);
        renderSection(groupActive, () -> {
            renderGroupTitle(group.title(), groupActive);
            renderListItems(group, groupActive, false);
        });
    }

    private <T> void renderCatalogGroup(SettingsTemplateModel.CatalogGroup<T> group) {
        if (group.scrollable()) {
            renderScrollableCatalogGroup(group);
            return;
        }
        boolean groupActive = safeBoolean(group.enabled(), true);
        renderSection(groupActive, () -> {
            renderGroupTitle(group.title(), groupActive);
            renderOptionEntries(group.controls(), groupActive, true);
            renderListItems(group.list(), groupActive, false);
        });
    }

    private <T> void renderScrollableCatalogGroup(SettingsTemplateModel.CatalogGroup<T> group) {
        boolean groupActive = safeBoolean(group.enabled(), true);
        renderSection(groupActive, () -> {
            renderGroupTitle(group.title(), groupActive);
            renderOptionEntries(group.controls(), groupActive, true);
            finishGridRow();
            SettingsLayoutItem listSlot = layout.nextIntersecting(scrollableCatalogHeight());
            if (!listSlot.visible()) {
                return;
            }
            RendererState outerState = saveState();
            List<SettingsDecoration> outerDecorations = new ArrayList<>(decorations);
            decorations.clear();
            int listX = contentX();
            int listWidth = contentWidth();
            int scrollOffset = screen == null ? 0 : screen.scrollOffsetFor(group.id());
            SettingsViewport listViewport = new SettingsViewport(listSlot.screenY(), listSlot.screenY() + listSlot.height(), scrollOffset);
            this.layout = new SettingsLayout(listSlot.screenY(), listViewport);
            this.viewport = listViewport;
            this.x = listX;
            this.width = listWidth;
            this.gridColumn = 0;
            List<AbstractWidget> previousRegionWidgets = activeScrollRegionWidgets;
            activeScrollRegionWidgets = new ArrayList<>();
            try {
                renderListItems(group.list(), groupActive, true);
                int contentHeight = layout.contentHeight();
                List<SettingsDecoration> regionDecorations = List.copyOf(decorations);
                List<AbstractWidget> regionWidgets = List.copyOf(activeScrollRegionWidgets);
                decorations.clear();
                decorations.addAll(outerDecorations);
                restoreState(outerState);
                scrollRegions.add(new SettingsScrollRegion(group.id(), listX, listSlot.screenY(), listWidth, listSlot.height(), contentHeight, listSlot.height(), regionDecorations, regionWidgets));
                if (screen != null) {
                    screen.registerScrollRegion(group.id(), contentHeight, listSlot.height());
                }
            } finally {
                activeScrollRegionWidgets = previousRegionWidgets;
            }
        });
    }

    private <T> void renderListItems(SettingsTemplateModel.ListGroup<T> group, boolean groupActive, boolean allowPartialVisibility) {
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
                SettingsListCard card = safeApply(group.carder(), value, SettingsListCard.text(UiText.literal("")));
                int cardHeight = SettingsListCardWidget.heightFor(font, card, bodyWidth);
                item = allowPartialVisibility ? layout.nextIntersecting(cardHeight) : layout.next(cardHeight);
                listWidget = new SettingsListCardWidget(rowX, item.screenY(), bodyWidth, cardHeight, card, () -> runActionAndRefresh(() -> safeAccept(group.onSelect(), value)));
            } else {
                Component valueLabel = safeLabel(group.labeler(), value);
                Component label = active ? Component.translatable("tianshu.gui.settings.list.selected", valueLabel) : valueLabel;
                int itemHeight = Math.max(SettingsLayoutMetrics.CONTROL_HEIGHT, SettingsListItemWidget.heightFor(font, label, bodyWidth));
                item = allowPartialVisibility ? layout.nextIntersecting(itemHeight) : layout.next(itemHeight);
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
            columnBottomTargetY = maxEndY > startY ? Math.max(startY, maxEndY - SettingsLayoutMetrics.GROUP_GAP) : -1;
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
        columnBottomTargetY = -1;
        layout.advanceTo(maxEndY);
        layout.groupGap();
    }

    private void renderControlRow(Component label, boolean active, ControlSizing sizing, java.util.function.Consumer<ControlRowItem> controlRenderer) {
        finishGridRow();
        int rowWidth = contentWidth();
        int availableControlWidth = Math.max(1, rowWidth - ROW_LABEL_MIN_WIDTH - SettingsLayoutMetrics.GAP);
        int controlWidth = sizing.resolve(availableControlWidth);
        int rowLabelWidth = Math.max(1, rowWidth - controlWidth - SettingsLayoutMetrics.GAP);
        int controlX = contentX() + rowWidth - controlWidth;
        int rowHeight = SettingsLayoutMetrics.CONTROL_HEIGHT;
        if (font.width(label) > rowLabelWidth) {
            rowHeight = Math.max(rowHeight, SettingsLayoutMetrics.ROW_HEIGHT);
        }
        SettingsLayoutItem row = layout.next(rowHeight);
        addIfVisible(new SettingsTextWidget(contentX(), row.screenY() + Math.max(0, (row.height() - SettingsLayoutMetrics.LABEL_HEIGHT) / 2), rowLabelWidth, SettingsLayoutMetrics.LABEL_HEIGHT, label, active ? 0xA0A0A0 : 0x606060), row, active);
        controlRenderer.accept(new ControlRowItem(row, row.screenY(), controlX, controlWidth));
        layout.gap();
    }

    private void renderGroupTitle(UiText title, boolean active) {
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

    private CycleButton.Builder<Boolean> booleanToggleBuilder(boolean selected) {
        return CycleButton.booleanBuilder(
                        Component.translatable("tianshu.gui.common.on"),
                        Component.translatable("tianshu.gui.common.off"))
                .withInitialValue(selected)
                .displayOnlyValue();
    }

    private record ControlRowItem(SettingsLayoutItem row, int screenY, int controlX, int controlWidth) {}

    private record ControlSizing(int minWidth, int preferredWidth, int maxWidth) {
        private int resolve(int availableWidth) {
            int boundedPreferred = Math.max(minWidth, Math.min(preferredWidth, maxWidth));
            int boundedAvailable = Math.max(1, Math.min(availableWidth, maxWidth));
            return Math.max(Math.min(minWidth, maxWidth), Math.min(boundedPreferred, boundedAvailable));
        }
    }

    private int scrollableCatalogHeight() {
        int viewportBottomContentY = viewport.bottomContentY() - SettingsLayoutMetrics.SECTION_PADDING;
        int columnBottomContentY = columnBottomTargetY > layout.cursorY() ? columnBottomTargetY : viewportBottomContentY;
        int targetBottom = Math.min(columnBottomContentY, viewportBottomContentY);
        int reservedBottomPadding = columnBottomTargetY > layout.cursorY() ? SettingsLayoutMetrics.SECTION_PADDING : 0;
        return Math.max(1, targetBottom - layout.cursorY() - reservedBottomPadding);
    }

    private RendererState saveState() {
        return new RendererState(layout, viewport, x, width, gridColumn, columnBottomTargetY);
    }

    private void restoreForColumn(RendererState state, int columnX, int columnWidth, int startY) {
        this.layout = new SettingsLayout(startY, viewport);
        this.x = columnX;
        this.width = columnWidth;
        this.gridColumn = 0;
    }

    private void restoreState(RendererState state) {
        this.layout = state.layout();
        this.viewport = state.viewport();
        this.x = state.x();
        this.width = state.width();
        this.gridColumn = state.gridColumn();
        this.columnBottomTargetY = state.columnBottomTargetY();
    }

    private record RendererState(SettingsLayout layout, SettingsViewport viewport, int x, int width, int gridColumn, int columnBottomTargetY) {}

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

    private <T> Component safeLabel(Function<T, UiText> function, T value) {
        try {
            UiText label = function == null ? null : function.apply(value);
            return label == null ? Component.literal(String.valueOf(value)) : NeoForgeUiText.toComponent(label);
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
                screen.requestRebuildCurrentPage();
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

    private Component safeComponent(UiText value, Component fallback) {
        return value == null ? fallback : NeoForgeUiText.toComponent(value);
    }

    private void addIfVisible(AbstractWidget widget, SettingsLayoutItem item, boolean active) {
        if (item.visible()) {
            widget.active = active;
            if (activeScrollRegionWidgets != null) {
                activeScrollRegionWidgets.add(widget);
            } else {
                screen.addSettingsWidget(widget);
            }
        }
    }
}
