package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class PresenceStatusTextElementRenderer implements PresenceHudElementRenderer {
    private static final int PANEL_BACKGROUND = 0x8A071116;
    private static final int TEXT_COLOR = 0xFFEAF7FF;
    private static final int ACTIVE_ACCENT = 0xD0A7F3FF;
    private static final int MIN_PANEL_WIDTH = 88;
    private static final int PANEL_HORIZONTAL_PADDING = 9;

    /** The last measured line; a steady frame does not re-shape the same string through the font. */
    private String measuredText = "";
    private int measuredWidth;

    @Override
    public PresenceHudElementType type() {
        return PresenceHudElementType.STATUS_TEXT;
    }

    @Override
    public void render(GuiGraphics graphics, Font font, PresenceHudElementFrame frame) {
        if (graphics == null || font == null || frame == null || frame.state() != PresenceHudElementState.ACTIVE) {
            return;
        }
        PresenceHudDisplay display = frame.display();
        if (display == null || !display.visible()) {
            return;
        }

        int textWidth = widthOf(font, display.text());
        int panelWidth = Math.max(MIN_PANEL_WIDTH, textWidth + PANEL_HORIZONTAL_PADDING * 2);
        int panelHeight = font.lineHeight + 10;
        int left = 12;
        int top = graphics.guiHeight() - panelHeight - 18;
        int right = left + panelWidth;
        int bottom = top + panelHeight;

        graphics.fill(left, top, right, bottom, PANEL_BACKGROUND);
        graphics.fill(left, bottom - 1, right, bottom, ACTIVE_ACCENT);
        graphics.drawString(font, display.text(), left + PANEL_HORIZONTAL_PADDING, top + 5, TEXT_COLOR, true);
    }

    private int widthOf(Font font, String text) {
        if (!text.equals(measuredText)) {
            measuredText = text;
            measuredWidth = font.width(text);
        }
        return measuredWidth;
    }
}
