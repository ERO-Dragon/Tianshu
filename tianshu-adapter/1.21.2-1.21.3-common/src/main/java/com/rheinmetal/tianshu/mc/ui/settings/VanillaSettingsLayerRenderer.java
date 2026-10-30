package com.rheinmetal.tianshu.mc.ui.settings;

import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerElement;
import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerModel;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.settings.layout.SettingsLayoutMetrics;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Draws one {@link SettingsLayerModel} as a centred panel.
 *
 * <p>It uses the same controls and metrics as the settings page — {@link SettingsTextWidget} for labels,
 * {@link SettingsListItemWidget} for rows, {@link SettingsLayoutMetrics} for spacing, the same button
 * styling and the same selected-row marker — so a layer looks like part of the page instead of a second
 * design. What it adds is only what a layer needs: a centred frame, one scrollable list window and
 * per-frame reads of the description.
 *
 * <p>Drawing order inside the frame is fixed: frame background, then text content (title, rows, empty
 * hint, status), then the input and button widgets the screen owns. The page underneath is deliberately
 * not drawn here: it is already on screen as a lower layer of the GUI stack, and painting it again would
 * put its text above this panel.
 *
 * <p>A layer describes at most one list; that is the minimal vocabulary this version supports.
 */
final class VanillaSettingsLayerRenderer {
    private static final int PANEL_MARGIN = 16;
    private static final int MIN_PANEL_WIDTH = 220;
    private static final int MAX_PANEL_WIDTH = 320;
    private static final int VISIBLE_ROWS = 8;
    private static final int ROW_HEIGHT = SettingsLayoutMetrics.CONTROL_HEIGHT;
    private static final int ACTION_WIDTH = 96;
    private static final int TEXT_MAX_LENGTH = 96;
    private static final int FRAME_COLOR = 0xFF141A1E;
    private static final int FRAME_BORDER_COLOR = 0xFF8A9AA5;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int LABEL_COLOR = 0xFFA0A0A0;
    private static final int ROW_COLOR = 0xFFD0D0D0;
    private static final int ROW_SELECTED_COLOR = 0xFFFFD479;
    private static final int STATUS_COLOR = 0xFFB9D5DE;
    private static final int EMPTY_COLOR = 0xFF9A9A9A;
    private static final int SCROLLBAR_TRACK_COLOR = 0x30FFFFFF;
    private static final int SCROLLBAR_THUMB_COLOR = 0x90FFFFFF;

    private final Consumer<AbstractWidget> widgetSink;
    private final Runnable closeRequest;
    private final Map<String, EditBox> inputById = new LinkedHashMap<>();
    private final Map<String, Button> buttonById = new LinkedHashMap<>();
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentX;
    private int contentWidth;
    private int listTop;
    private int listHeight;
    private int statusTop;
    private int listOffset;
    private SettingsLayerElement.List list;
    private EditBox initialFocus;
    private List<String> rowIds = List.of();
    private List<SettingsListItemWidget> rows = List.of();
    /** The selection the current rows were built for: row colour is baked into the component, not read per frame. */
    private String rowSelection = "";
    /** Memo of the last visible-items computation: {@code refresh} runs on every frame of the layer. */
    private SettingsLayerElement.List cachedFor;
    private List<String> cachedSource;
    private String cachedTerm;
    private List<String> cachedVisible = List.of();

    VanillaSettingsLayerRenderer(Consumer<AbstractWidget> widgetSink, Runnable closeRequest) {
        this.widgetSink = widgetSink == null ? widget -> { } : widgetSink;
        this.closeRequest = closeRequest == null ? () -> { } : closeRequest;
    }

    /**
     * Creates the panel and its controls. Called once per window, from the screen's own init.
     *
     * <p>The frame is measured before it is placed so it can be centred; the measuring and the placing
     * walks share {@link #heightOf}, so they cannot disagree about how tall the panel is.
     */
    void build(SettingsLayerModel layer, Font font, int screenWidth, int screenHeight) {
        panelWidth = Math.max(MIN_PANEL_WIDTH, Math.min(MAX_PANEL_WIDTH, screenWidth - 2 * PANEL_MARGIN));
        contentWidth = panelWidth - 2 * SettingsLayoutMetrics.SECTION_PADDING;
        int height = SettingsLayoutMetrics.SECTION_PADDING * 2
                + font.lineHeight + SettingsLayoutMetrics.GROUP_GAP;
        for (SettingsLayerElement element : layer.elements()) {
            height += heightOf(element, font) + SettingsLayoutMetrics.GAP;
        }
        panelHeight = height;
        panelX = (screenWidth - panelWidth) / 2;
        panelY = Math.max(PANEL_MARGIN, (screenHeight - panelHeight) / 2);
        contentX = panelX + SettingsLayoutMetrics.SECTION_PADDING;

        int cursorY = panelY + SettingsLayoutMetrics.SECTION_PADDING
                + font.lineHeight + SettingsLayoutMetrics.GROUP_GAP;
        for (SettingsLayerElement element : layer.elements()) {
            cursorY = place(element, font, cursorY) + SettingsLayoutMetrics.GAP;
        }
        refresh(layer);
    }

    /**
     * Re-reads the description: button availability, input text and the visible rows.
     *
     * <p>Cheap by design — the window calls it every frame, so nothing here may rebuild a widget. An
     * input is only written when its value actually differs, which keeps the caret of the field the
     * player is typing in exactly where they left it.
     */
    void refresh(SettingsLayerModel layer) {
        for (SettingsLayerElement element : layer.elements()) {
            if (element instanceof SettingsLayerElement.Actions actions) {
                refreshActions(actions);
            } else if (element instanceof SettingsLayerElement.Text text) {
                refreshInput(text.id(), text.currentValue());
            }
        }
        if (list != null) {
            if (list.searchable()) {
                refreshInput(list.id() + ".search", list.term());
            }
            refreshRows();
        }
    }

    /** Panel frame, title and text content; the controls are drawn by the screen after this. */
    void render(GuiGraphics graphics, Font font, SettingsLayerModel layer, int mouseX, int mouseY) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, FRAME_COLOR);
        graphics.renderOutline(panelX, panelY, panelWidth, panelHeight, FRAME_BORDER_COLOR);
        graphics.drawCenteredString(font, UiTexts.toComponent(layer.title()),
                panelX + panelWidth / 2, panelY + SettingsLayoutMetrics.SECTION_PADDING, TITLE_COLOR);
        renderRows(graphics, font, mouseX, mouseY);
        renderEmptyHint(graphics, font);
        renderStatus(graphics, font, layer);
    }

    /** A click on a row selects it; every other click belongs to the screen's widget pass. */
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || list == null || !insideList(mouseX, mouseY)) {
            return false;
        }
        int index = (int) ((mouseY - listTop) / ROW_HEIGHT) + listOffset;
        if (index < 0 || index >= rowIds.size()) {
            return false;
        }
        list.onSelect().accept(rowIds.get(index));
        return true;
    }

    /** The wheel scrolls the list window only while the pointer is over it. */
    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (list == null || !insideList(mouseX, mouseY)) {
            return false;
        }
        int maxOffset = Math.max(0, rowIds.size() - VISIBLE_ROWS);
        int next = Math.max(0, Math.min(maxOffset, listOffset - (int) Math.signum(scrollY)));
        if (next != listOffset) {
            listOffset = next;
            rows = rowWidgets();
        }
        return true;
    }

    /** Whether a point is inside the panel; the screen closes on a click outside it. */
    boolean contains(double mouseX, double mouseY) {
        return mouseX >= panelX && mouseX < panelX + panelWidth
                && mouseY >= panelY && mouseY < panelY + panelHeight;
    }

    /** The field the window focuses on open, so typing reaches the search box without a click. */
    EditBox initialFocus() {
        return initialFocus;
    }

    private int heightOf(SettingsLayerElement element, Font font) {
        if (element instanceof SettingsLayerElement.Text) {
            return SettingsLayoutMetrics.LABEL_HEIGHT + SettingsLayoutMetrics.GAP
                    + SettingsLayoutMetrics.CONTROL_HEIGHT;
        }
        if (element instanceof SettingsLayerElement.List described) {
            int height = font.lineHeight + SettingsLayoutMetrics.GAP + VISIBLE_ROWS * ROW_HEIGHT;
            if (described.searchable()) {
                height += SettingsLayoutMetrics.LABEL_HEIGHT + SettingsLayoutMetrics.GAP
                        + SettingsLayoutMetrics.CONTROL_HEIGHT + SettingsLayoutMetrics.GAP;
            }
            return height;
        }
        if (element instanceof SettingsLayerElement.Actions actions) {
            return actionRows(actions) * SettingsLayoutMetrics.CONTROL_HEIGHT
                    + Math.max(0, actionRows(actions) - 1) * SettingsLayoutMetrics.GAP;
        }
        return font.lineHeight;
    }

    private int place(SettingsLayerElement element, Font font, int cursorY) {
        if (element instanceof SettingsLayerElement.Text text) {
            addLabel(text.label(), cursorY);
            int inputY = cursorY + SettingsLayoutMetrics.LABEL_HEIGHT + SettingsLayoutMetrics.GAP;
            EditBox box = new EditBox(font, contentX, inputY, contentWidth,
                    SettingsLayoutMetrics.CONTROL_HEIGHT, UiTexts.toComponent(text.hint()));
            box.setMaxLength(TEXT_MAX_LENGTH);
            box.setValue(text.currentValue());
            box.setResponder(text.setter());
            inputById.put(text.id(), box);
            widgetSink.accept(box);
            if (initialFocus == null) {
                initialFocus = box;
            }
            return inputY + SettingsLayoutMetrics.CONTROL_HEIGHT;
        }
        if (element instanceof SettingsLayerElement.List described) {
            int cursor = cursorY;
            if (described.searchable()) {
                SettingsLayerElement.List.Search search = described.search();
                addLabel(search.label(), cursor);
                int inputY = cursor + SettingsLayoutMetrics.LABEL_HEIGHT + SettingsLayoutMetrics.GAP;
                EditBox box = new EditBox(font, contentX, inputY, contentWidth,
                        SettingsLayoutMetrics.CONTROL_HEIGHT, UiTexts.toComponent(search.label()));
                box.setMaxLength(TEXT_MAX_LENGTH);
                box.setValue(search.currentTerm());
                box.setResponder(search.setTerm());
                inputById.put(described.id() + ".search", box);
                widgetSink.accept(box);
                if (initialFocus == null) {
                    initialFocus = box;
                }
                cursor = inputY + SettingsLayoutMetrics.CONTROL_HEIGHT + SettingsLayoutMetrics.GAP;
            }
            addLabel(described.title(), cursor);
            list = described;
            listTop = cursor + font.lineHeight + SettingsLayoutMetrics.GAP;
            listHeight = VISIBLE_ROWS * ROW_HEIGHT;
            return listTop + listHeight;
        }
        if (element instanceof SettingsLayerElement.Actions actions) {
            int cursor = cursorY;
            int x = contentX;
            for (SettingsLayerElement.Action action : actions.actions()) {
                if (x + ACTION_WIDTH > contentX + contentWidth) {
                    x = contentX;
                    cursor += SettingsLayoutMetrics.CONTROL_HEIGHT + SettingsLayoutMetrics.GAP;
                }
                Button button = Button.builder(UiTexts.toComponent(action.label()), clicked -> runAction(action))
                        .pos(x, cursor)
                        .size(ACTION_WIDTH, SettingsLayoutMetrics.CONTROL_HEIGHT)
                        .build();
                buttonById.put(action.id(), button);
                widgetSink.accept(button);
                x += ACTION_WIDTH + SettingsLayoutMetrics.GAP;
            }
            return cursor + SettingsLayoutMetrics.CONTROL_HEIGHT;
        }
        statusTop = cursorY;
        return cursorY + font.lineHeight;
    }

    private int actionRows(SettingsLayerElement.Actions actions) {
        int perRow = Math.max(1, (contentWidth + SettingsLayoutMetrics.GAP)
                / (ACTION_WIDTH + SettingsLayoutMetrics.GAP));
        return Math.max(1, (actions.actions().size() + perRow - 1) / perRow);
    }

    private void refreshActions(SettingsLayerElement.Actions actions) {
        for (SettingsLayerElement.Action action : actions.actions()) {
            Button button = buttonById.get(action.id());
            if (button != null) {
                button.active = action.isEnabled();
            }
        }
    }

    private void refreshInput(String id, String value) {
        EditBox box = inputById.get(id);
        if (box != null && !box.getValue().equals(value)) {
            box.setValue(value);
        }
    }

    private void refreshRows() {
        List<String> visible = visibleItems(list);
        String selection = list == null ? "" : list.currentSelection();
        if (visible.equals(rowIds) && selection.equals(rowSelection) && !rows.isEmpty()) {
            return;
        }
        rowIds = visible;
        rowSelection = selection;
        listOffset = Math.max(0, Math.min(listOffset, Math.max(0, visible.size() - VISIBLE_ROWS)));
        rows = rowWidgets();
    }

    /**
     * The list filtered by its search term, memoised for the frame loop.
     *
     * <p>{@link #refreshRows()} runs on every frame, and recomputing the filter meant lower-casing every item
     * of the source once per frame. The memo is keyed on the source instance and the term, so a list whose
     * provider returns a fresh instance simply misses (and behaves exactly as before).</p>
     */
    private List<String> visibleItems(SettingsLayerElement.List source) {
        if (source == null) {
            return List.of();
        }
        String term = source.term();
        List<String> items = source.items().get();
        if (cachedFor == source && cachedSource == items && java.util.Objects.equals(cachedTerm, term)) {
            return cachedVisible;
        }
        List<String> computed = source.visibleItems();
        cachedFor = source;
        cachedSource = items;
        cachedTerm = term;
        cachedVisible = computed;
        return computed;
    }

    private List<SettingsListItemWidget> rowWidgets() {
        if (list == null) {
            return List.of();
        }
        String selected = list.currentSelection();
        int visibleCount = Math.min(VISIBLE_ROWS, Math.max(0, rowIds.size() - listOffset));
        List<SettingsListItemWidget> widgets = new ArrayList<>(visibleCount);
        for (int row = 0; row < visibleCount; row++) {
            String value = rowIds.get(listOffset + row);
            Component label = Component.literal(value);
            boolean isSelected = value.equals(selected);
            Component shown = isSelected
                    ? Component.translatable("tianshu.gui.settings.list.selected", label)
                    : label;
            widgets.add(new SettingsListItemWidget(contentX, listTop + row * ROW_HEIGHT, contentWidth, ROW_HEIGHT,
                    shown, isSelected ? ROW_SELECTED_COLOR : ROW_COLOR));
        }
        return List.copyOf(widgets);
    }

    private void renderRows(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (rows.isEmpty()) {
            return;
        }
        graphics.enableScissor(contentX, listTop, contentX + contentWidth, listTop + listHeight);
        try {
            for (SettingsListItemWidget row : rows) {
                row.render(graphics, mouseX, mouseY, 0.0F);
            }
        } finally {
            graphics.disableScissor();
        }
        renderScrollbar(graphics);
    }

    private void renderScrollbar(GuiGraphics graphics) {
        if (rowIds.size() <= VISIBLE_ROWS) {
            return;
        }
        int trackX = contentX + contentWidth - 2;
        int thumbHeight = Math.max(12, listHeight * VISIBLE_ROWS / rowIds.size());
        int maxOffset = rowIds.size() - VISIBLE_ROWS;
        int thumbTop = listTop + (listHeight - thumbHeight) * listOffset / maxOffset;
        graphics.fill(trackX, listTop, trackX + 2, listTop + listHeight, SCROLLBAR_TRACK_COLOR);
        graphics.fill(trackX, thumbTop, trackX + 2, thumbTop + thumbHeight, SCROLLBAR_THUMB_COLOR);
    }

    private void renderEmptyHint(GuiGraphics graphics, Font font) {
        if (list == null || !rowIds.isEmpty()) {
            return;
        }
        Component hint = UiTexts.toComponent(list.emptyText());
        if (hint.getString().isEmpty()) {
            return;
        }
        graphics.drawCenteredString(font, hint, contentX + contentWidth / 2,
                listTop + (listHeight - font.lineHeight) / 2, EMPTY_COLOR);
    }

    private void renderStatus(GuiGraphics graphics, Font font, SettingsLayerModel layer) {
        for (SettingsLayerElement element : layer.elements()) {
            if (!(element instanceof SettingsLayerElement.Status status)) {
                continue;
            }
            Component text = UiTexts.toComponent(status.currentText());
            if (text.getString().isEmpty()) {
                return;
            }
            graphics.enableScissor(contentX, statusTop, contentX + contentWidth, statusTop + font.lineHeight);
            try {
                graphics.drawString(font, text, contentX, statusTop, STATUS_COLOR, false);
            } finally {
                graphics.disableScissor();
            }
            return;
        }
    }

    private void addLabel(UiText text, int y) {
        Component label = UiTexts.toComponent(text);
        if (label.getString().isEmpty()) {
            return;
        }
        widgetSink.accept(new SettingsTextWidget(contentX, y, contentWidth,
                SettingsLayoutMetrics.LABEL_HEIGHT, label, LABEL_COLOR));
    }

    private void runAction(SettingsLayerElement.Action action) {
        Runnable handler = action.run();
        boolean closes = action.closesLayer();
        try {
            handler.run();
        } finally {
            // A layer must never stay open because its action threw: closing is the caller's intent.
            if (closes) {
                closeRequest.run();
            }
        }
    }

    private boolean insideList(double mouseX, double mouseY) {
        return listTop > 0
                && mouseX >= contentX && mouseX < contentX + contentWidth
                && mouseY >= listTop && mouseY < listTop + listHeight;
    }
}
