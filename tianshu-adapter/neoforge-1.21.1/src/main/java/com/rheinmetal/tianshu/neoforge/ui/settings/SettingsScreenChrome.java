package com.rheinmetal.tianshu.neoforge.ui.settings;

import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.layout.ScrollState;
import com.rheinmetal.tianshu.client.settings.layout.SettingsScreenLayout;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class SettingsScreenChrome {
    private static final ResourceLocation LIST_BACKGROUND = ResourceLocation.withDefaultNamespace("textures/gui/menu_list_background.png");
    private static final int HEADER_BACKGROUND = 0x66000000;
    private static final int PANEL_BORDER = 0x66000000;

    public SettingsScreenLayout layout(int screenWidth, int screenHeight) {
        int margin = Math.max(10, Math.min(20, screenWidth / 28));
        int top = 32;
        int bottom = 38;
        int minContentHeight = 120;
        int contentTop = top;
        int contentBottom = Math.max(contentTop + minContentHeight, screenHeight - bottom);
        if (contentBottom > screenHeight - 32) {
            contentBottom = Math.max(contentTop + 80, screenHeight - 32);
        }
        int gap = Math.max(8, Math.min(14, screenWidth / 60));
        int availableWidth = Math.max(260, screenWidth - margin * 2 - gap);
        int leftWidth = Math.max(112, Math.min(150, availableWidth / 4));
        int leftX = margin;
        int rightX = leftX + leftWidth + gap;
        int rightWidth = Math.max(120, screenWidth - rightX - margin);
        return new SettingsScreenLayout(leftX, rightX, contentTop, contentBottom, leftWidth, rightWidth);
    }

    public void drawFrame(GuiGraphics guiGraphics, SettingsScreenLayout layout, ScrollState rightPanelScroll) {
        drawListBackground(guiGraphics, layout.leftX(), layout.leftListTop(), layout.leftWidth(), layout.leftListHeight());
        drawListBackground(guiGraphics, layout.rightX(), layout.viewportTop(), layout.rightWidth(), layout.viewportBottom() - layout.viewportTop());
        guiGraphics.fill(layout.leftX(), layout.contentTop(), layout.leftX() + layout.leftWidth(), layout.leftListTop(), HEADER_BACKGROUND);
        guiGraphics.fill(layout.rightX(), layout.contentTop(), layout.rightX() + layout.rightWidth(), layout.viewportTop(), HEADER_BACKGROUND);
        guiGraphics.fill(layout.leftX(), layout.leftListTop(), layout.leftX() + layout.leftWidth(), layout.leftListTop() + 1, PANEL_BORDER);
        guiGraphics.fill(layout.rightX(), layout.viewportTop(), layout.rightX() + layout.rightWidth(), layout.viewportTop() + 1, PANEL_BORDER);
        drawScrollbar(guiGraphics, layout, rightPanelScroll);
    }

    public void drawForeground(GuiGraphics guiGraphics, Font font, int screenWidth, int screenHeight, Component title, Component leftTitle, ModuleSettingsCategory selected, Component status) {
        guiGraphics.drawCenteredString(font, title, screenWidth / 2, 12, 0xFFFFFF);
        if (selected != null) {
            SettingsScreenLayout layout = layout(screenWidth, screenHeight);
            guiGraphics.drawString(font, leftTitle, layout.leftX() + 6, layout.contentTop() + 9, 0xFFFFFF, false);
            guiGraphics.drawString(font, NeoForgeUiText.toComponent(selected.title()), layout.rightX() + 8, layout.contentTop() + 10, 0xFFFFFF, false);
        }
        if (!status.getString().isBlank()) {
            SettingsScreenLayout layout = layout(screenWidth, screenHeight);
            guiGraphics.drawString(font, status, layout.leftX(), layout.actionsY() + 6, 0xFFFF66, false);
        }
    }

    public void drawOverlay(GuiGraphics guiGraphics, SettingsScreenLayout layout, ScrollState rightPanelScroll) {
        guiGraphics.renderOutline(layout.leftX(), layout.contentTop(), layout.leftWidth(), layout.panelHeight(), PANEL_BORDER);
        guiGraphics.renderOutline(layout.rightX(), layout.contentTop(), layout.rightWidth(), layout.panelHeight(), PANEL_BORDER);
        drawScrollbar(guiGraphics, layout, rightPanelScroll);
    }

    private void drawListBackground(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        Screen.renderMenuBackgroundTexture(guiGraphics, LIST_BACKGROUND, x, y, x, y, width, height);
    }

    private void drawScrollbar(GuiGraphics guiGraphics, SettingsScreenLayout layout, ScrollState scroll) {
        if (scroll.contentHeight() <= scroll.viewportHeight() || scroll.viewportHeight() <= 0) {
            return;
        }
        int scrollbarX = layout.rightX() + layout.rightWidth() - 6;
        int trackTop = layout.viewportTop();
        int trackBottom = layout.viewportBottom();
        int trackHeight = Math.max(1, trackBottom - trackTop);
        int thumbHeight = Math.max(18, trackHeight * scroll.viewportHeight() / Math.max(1, scroll.contentHeight()));
        int maxScroll = Math.max(1, scroll.contentHeight() - scroll.viewportHeight());
        int thumbY = trackTop + (trackHeight - thumbHeight) * scroll.offset() / maxScroll;
        guiGraphics.fill(scrollbarX, trackTop, scrollbarX + 3, trackBottom, 0x66000000);
        guiGraphics.fill(scrollbarX, thumbY, scrollbarX + 3, thumbY + thumbHeight, 0xFFB0B0B0);
    }
}


