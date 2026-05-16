package com.rheinmetal.tianshu.client.gui.settings.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

final class SettingsTextWidget extends AbstractWidget {
    private final int color;

    SettingsTextWidget(int x, int y, int width, int height, Component message, int color) {
        super(x, y, width, height, message);
        this.color = color;
        this.active = false;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.drawString(Minecraft.getInstance().font, getMessage(), getX(), getY() + Math.max(0, (getHeight() - 8) / 2), color, false);
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
    }
}
