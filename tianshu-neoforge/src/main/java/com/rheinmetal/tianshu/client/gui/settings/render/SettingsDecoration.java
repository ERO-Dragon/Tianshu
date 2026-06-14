package com.rheinmetal.tianshu.client.gui.settings.render;

import net.minecraft.client.gui.GuiGraphics;

public record SettingsDecoration(int x, int y, int width, int height, int backgroundColor, int lightBorderColor, int darkBorderColor) {
    public void drawBackground(GuiGraphics guiGraphics) {
        if (width <= 0 || height <= 0) {
            return;
        }
        guiGraphics.fill(x, y, x + width, y + height, backgroundColor);
    }

    public void drawBorder(GuiGraphics guiGraphics) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int right = x + width;
        int bottom = y + height;
        guiGraphics.fill(x, y, right, y + 1, lightBorderColor);
        guiGraphics.fill(x, y, x + 1, bottom, lightBorderColor);
        guiGraphics.fill(x, bottom - 1, right, bottom, darkBorderColor);
        guiGraphics.fill(right - 1, y, right, bottom, darkBorderColor);
    }
}
