package com.rheinmetal.tianshu.client.gui.settings.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

final class SettingsSelectionOverlay<T> extends net.minecraft.client.gui.screens.Screen {
    private static final int ENTRY_HEIGHT = 18;
    private static final int PADDING = 6;
    private static final int MAX_VISIBLE = 10;
    private static final int OVERLAY_WIDTH = 180;
    private static final int BACKGROUND = 0xF0101010;
    private static final int BORDER = 0xFF808080;
    private static final int HOVER_BG = 0x40FFFFFF;
    private static final int SELECTED_COLOR = 0xFFFFA0;
    private static final int NORMAL_COLOR = 0xE0E0E0;

    private final net.minecraft.client.gui.screens.Screen parent;
    private final Component title;
    private final List<T> values;
    private final T selected;
    private final Function<T, Component> labeler;
    private final Consumer<T> onSelect;
    private int scrollOffset = 0;
    private int contentHeight = 0;

    SettingsSelectionOverlay(net.minecraft.client.gui.screens.Screen parent, Component title, List<T> values, T selected, Function<T, Component> labeler, Consumer<T> onSelect) {
        super(title == null ? Component.empty() : title);
        this.parent = parent;
        this.title = title == null ? Component.empty() : title;
        this.values = values == null ? List.of() : values;
        this.selected = selected;
        this.labeler = labeler == null ? value -> Component.literal(String.valueOf(value)) : labeler;
        this.onSelect = onSelect == null ? value -> {} : onSelect;
    }

    @Override
    protected void init() {
        super.init();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        Font font = Minecraft.getInstance().font;
        int overlayX = (width - OVERLAY_WIDTH) / 2;
        int visibleCount = Math.min(MAX_VISIBLE, values.size());
        int titleHeight = font.lineHeight + PADDING * 2;
        contentHeight = titleHeight + visibleCount * ENTRY_HEIGHT + PADDING;
        int overlayY = (height - contentHeight) / 2;
        guiGraphics.fill(overlayX, overlayY, overlayX + OVERLAY_WIDTH, overlayY + contentHeight, BACKGROUND);
        guiGraphics.renderOutline(overlayX, overlayY, OVERLAY_WIDTH, contentHeight, BORDER);
        guiGraphics.drawCenteredString(font, title, width / 2, overlayY + PADDING, 0xFFFFFF);
        int entryY = overlayY + titleHeight;
        int maxScroll = Math.max(0, values.size() - MAX_VISIBLE);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        guiGraphics.enableScissor(overlayX + 1, entryY, overlayX + OVERLAY_WIDTH - 1, overlayY + contentHeight - 1);
        for (int i = 0; i < visibleCount; i++) {
            int idx = i + scrollOffset;
            if (idx >= values.size()) break;
            T value = values.get(idx);
            Component label = labeler.apply(value);
            boolean isSelected = value != null && value.equals(selected);
            int rowY = entryY + i * ENTRY_HEIGHT;
            boolean hovered = mouseX >= overlayX && mouseX < overlayX + OVERLAY_WIDTH && mouseY >= rowY && mouseY < rowY + ENTRY_HEIGHT;
            if (hovered) {
                guiGraphics.fill(overlayX + 1, rowY, overlayX + OVERLAY_WIDTH - 1, rowY + ENTRY_HEIGHT, HOVER_BG);
            }
            int color = isSelected ? SELECTED_COLOR : NORMAL_COLOR;
            guiGraphics.drawString(font, label, overlayX + PADDING + 4, rowY + (ENTRY_HEIGHT - font.lineHeight) / 2, color, false);
        }
        guiGraphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Font font = Minecraft.getInstance().font;
        int overlayX = (width - OVERLAY_WIDTH) / 2;
        int visibleCount = Math.min(MAX_VISIBLE, values.size());
        int titleHeight = font.lineHeight + PADDING * 2;
        contentHeight = titleHeight + visibleCount * ENTRY_HEIGHT + PADDING;
        int overlayY = (height - contentHeight) / 2;
        int entryY = overlayY + titleHeight;
        if (mouseX >= overlayX && mouseX < overlayX + OVERLAY_WIDTH && mouseY >= entryY && mouseY < overlayY + contentHeight) {
            int idx = (int) ((mouseY - entryY) / ENTRY_HEIGHT) + scrollOffset;
            if (idx >= 0 && idx < values.size()) {
                T value = values.get(idx);
                onSelect.accept(value);
                close();
                return true;
            }
        }
        if (mouseX < overlayX || mouseX >= overlayX + OVERLAY_WIDTH || mouseY < overlayY || mouseY >= overlayY + contentHeight) {
            close();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollOffset -= (int) Math.signum(scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void close() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
