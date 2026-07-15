package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.model.PresenceSeverity;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class PresenceStatusTextElementRenderer implements PresenceHudElementRenderer {
    private static final int PANEL_BACKGROUND = 0x8A071116;
    private static final int TEXT_COLOR = 0xFFEAF7FF;
    private static final int ERROR_ACCENT = 0xD0FF7777;
    private static final int ACTIVE_ACCENT = 0xD0A7F3FF;

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

        int textWidth = font.width(display.text());
        int panelWidth = Math.max(88, textWidth + 18);
        int panelHeight = font.lineHeight + 10;
        int left = 12;
        int top = graphics.guiHeight() - panelHeight - 18;
        int right = left + panelWidth;
        int bottom = top + panelHeight;

        graphics.fill(left, top, right, bottom, PANEL_BACKGROUND);
        graphics.fill(left, bottom - 1, right, bottom, display.severity() == PresenceSeverity.ERROR ? ERROR_ACCENT : ACTIVE_ACCENT);
        graphics.drawString(font, display.text(), left + 9, top + 5, TEXT_COLOR, true);
    }
}
