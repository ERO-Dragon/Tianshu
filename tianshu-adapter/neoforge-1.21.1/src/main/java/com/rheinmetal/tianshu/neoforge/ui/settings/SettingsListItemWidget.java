package com.rheinmetal.tianshu.neoforge.ui.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

final class SettingsListItemWidget extends AbstractWidget {
    private static final int PADDING_X = 6;
    private static final int PADDING_Y = 4;
    private static final int LINE_HEIGHT = 9;
    private static final int BACKGROUND = 0x18000000;
    private static final int BORDER_LIGHT = 0x33FFFFFF;
    private static final int BORDER_DARK = 0x55000000;
    private final List<FormattedCharSequence> lines;
    private final int color;
    private final Runnable onClick;

    SettingsListItemWidget(int x, int y, int width, int height, Component message, int color) {
        this(x, y, width, height, message, color, null);
    }

    SettingsListItemWidget(int x, int y, int width, int height, Component message, int color, Runnable onClick) {
        super(x, y, width, height, message == null ? Component.empty() : message);
        Font font = Minecraft.getInstance().font;
        this.lines = font.split(getMessage(), Math.max(1, width - PADDING_X * 2));
        this.color = color;
        this.onClick = onClick;
        this.active = false;
    }

    static int heightFor(Font font, Component message, int width) {
        List<FormattedCharSequence> lines = font.split(message == null ? Component.empty() : message, Math.max(1, width - PADDING_X * 2));
        int shownLines = Math.max(1, Math.min(4, lines.size()));
        return PADDING_Y * 2 + shownLines * LINE_HEIGHT;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int right = getX() + getWidth();
        int bottom = getY() + getHeight();
        guiGraphics.fill(getX(), getY(), right, bottom, BACKGROUND);
        guiGraphics.fill(getX(), getY(), right, getY() + 1, BORDER_LIGHT);
        guiGraphics.fill(getX(), getY(), getX() + 1, bottom, BORDER_LIGHT);
        guiGraphics.fill(getX(), bottom - 1, right, bottom, BORDER_DARK);
        guiGraphics.fill(right - 1, getY(), right, bottom, BORDER_DARK);

        Font font = Minecraft.getInstance().font;
        int lineY = getY() + PADDING_Y;
        int maxLines = Math.max(1, (getHeight() - PADDING_Y * 2) / LINE_HEIGHT);
        int count = Math.min(maxLines, lines.size());
        guiGraphics.enableScissor(getX(), getY(), right, bottom);
        for (int i = 0; i < count; i++) {
            guiGraphics.drawString(font, lines.get(i), getX() + PADDING_X, lineY, color, false);
            lineY += LINE_HEIGHT;
        }
        guiGraphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (active && clicked(mouseX, mouseY) && onClick != null) {
            onClick.run();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
    }
}
