package com.rheinmetal.tianshu.client.gui.settings.layout;

import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class SettingsScreenChrome {
    public SettingsScreenLayout layout(int screenWidth, int screenHeight) {
        int margin = Math.max(12, Math.min(24, screenWidth / 24));
        int top = 36;
        int bottom = 52;
        int minContentHeight = 120;
        int contentTop = top + 22;
        int contentBottom = Math.max(contentTop + minContentHeight, screenHeight - bottom);
        if (contentBottom > screenHeight - 32) {
            contentBottom = Math.max(contentTop + 80, screenHeight - 32);
        }
        int gap = Math.max(8, Math.min(14, screenWidth / 60));
        int availableWidth = Math.max(260, screenWidth - margin * 2 - gap);
        int leftWidth = Math.max(104, Math.min(150, availableWidth / 3));
        int leftX = margin;
        int rightX = leftX + leftWidth + gap;
        int rightWidth = Math.max(120, screenWidth - rightX - margin);
        return new SettingsScreenLayout(leftX, rightX, contentTop, contentBottom, leftWidth, rightWidth);
    }

    public void drawFrame(GuiGraphics guiGraphics, SettingsScreenLayout layout, ScrollState rightPanelScroll) {
        guiGraphics.fill(layout.leftX() - 1, layout.contentTop() - 1, layout.leftX() + layout.leftWidth() + 1, layout.contentBottom() + 1, 0x55FFFFFF);
        guiGraphics.fill(layout.leftX(), layout.contentTop(), layout.leftX() + layout.leftWidth(), layout.contentBottom(), 0xAA000000);
        guiGraphics.fill(layout.rightX() - 1, layout.contentTop() - 1, layout.rightX() + layout.rightWidth() + 1, layout.contentBottom() + 1, 0x55FFFFFF);
        guiGraphics.fill(layout.rightX(), layout.contentTop(), layout.rightX() + layout.rightWidth(), layout.contentBottom(), 0xAA000000);
        guiGraphics.fill(layout.rightX(), layout.actionsY() - 4, layout.rightX() + layout.rightWidth(), layout.actionsY() + 24, 0x66000000);
        drawScrollbar(guiGraphics, layout, rightPanelScroll);
    }

    public void drawForeground(GuiGraphics guiGraphics, Font font, int screenWidth, int screenHeight, Component title, Component leftTitle, ModuleSettingsCategory selected, Component status) {
        guiGraphics.drawCenteredString(font, title, screenWidth / 2, 14, 0xFFFFFF);
        guiGraphics.drawString(font, leftTitle, 24, 42, 0xFFFFFF, false);
        if (selected != null) {
            SettingsScreenLayout layout = layout(screenWidth, screenHeight);
            guiGraphics.drawString(font, selected.title(), layout.rightX() + 10, 42, 0xFFFFFF, false);
            if (!selected.description().getString().isBlank()) {
                guiGraphics.drawString(font, selected.description(), layout.rightX() + 10, 56, 0xA0A0A0, false);
            }
        }
        if (!status.getString().isBlank()) {
            SettingsScreenLayout layout = layout(screenWidth, screenHeight);
            guiGraphics.drawString(font, status, 24, layout.actionsY() + 6, 0xFFFF66, false);
        }
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
        guiGraphics.fill(scrollbarX, thumbY, scrollbarX + 3, thumbY + thumbHeight, 0xAAFFFFFF);
    }
}


