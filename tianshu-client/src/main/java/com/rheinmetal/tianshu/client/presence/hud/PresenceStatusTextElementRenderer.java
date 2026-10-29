package com.rheinmetal.tianshu.client.presence.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudSurface;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;

/**
 * The status-text element: a small panel in the lower-left, visible only when the product state has words to show.
 *
 * <p>An idle HUD therefore keeps its icon and drops the line, which is why the element exists separately from the
 * icon instead of being a field of it. The panel's width follows the measured text, so the platform measures and the
 * element lays out.</p>
 */
public final class PresenceStatusTextElementRenderer implements PresenceHudElementRenderer {
    private static final int PANEL_BACKGROUND = 0x8A071116;
    private static final int TEXT_COLOR = 0xFFEAF7FF;
    private static final int ACTIVE_ACCENT = 0xD0A7F3FF;
    private static final int MIN_PANEL_WIDTH = 88;
    private static final int PANEL_HORIZONTAL_PADDING = 9;
    private static final int PANEL_LEFT = 12;
    private static final int PANEL_BOTTOM_MARGIN = 18;

    @Override
    public PresenceHudElementType type() {
        return PresenceHudElementType.STATUS_TEXT;
    }

    @Override
    public void render(HudSurface surface, PresenceHudElementFrame frame) {
        if (surface == null || frame == null || frame.state() != PresenceHudElementState.ACTIVE) {
            return;
        }
        PresenceHudDisplay display = frame.display();
        if (display == null || !display.visible() || display.text().isBlank()) {
            return;
        }

        int textWidth = surface.textWidth(display.text());
        int panelWidth = Math.max(MIN_PANEL_WIDTH, textWidth + PANEL_HORIZONTAL_PADDING * 2);
        int panelHeight = surface.lineHeight() + 10;
        int left = PANEL_LEFT;
        int top = surface.height() - panelHeight - PANEL_BOTTOM_MARGIN;
        int right = left + panelWidth;
        int bottom = top + panelHeight;

        surface.fill(left, top, right, bottom, PANEL_BACKGROUND);
        surface.fill(left, bottom - 1, right, bottom, ACTIVE_ACCENT);
        surface.text(display.text(), left + PANEL_HORIZONTAL_PADDING, top + 5, TEXT_COLOR, true);
    }
}
