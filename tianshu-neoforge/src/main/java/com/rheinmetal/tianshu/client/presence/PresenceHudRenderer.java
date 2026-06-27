package com.rheinmetal.tianshu.client.presence;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class PresenceHudRenderer implements PresenceRenderer {
    private static final int PANEL_BACKGROUND = 0x8A071116;
    private static final int TEXT_COLOR = 0xFFEAF7FF;
    private static final int ERROR_ACCENT = 0xD0FF7777;
    private static final int ACTIVE_ACCENT = 0xD0A7F3FF;

    private final PresenceStateStore stateStore;
    private final PresenceDisplayPolicy displayPolicy;

    public PresenceHudRenderer(PresenceStateStore stateStore, PresenceDisplayPolicy displayPolicy) {
        this.stateStore = stateStore;
        this.displayPolicy = displayPolicy;
    }

    @Override
    public void render(GuiGraphics graphics, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        PresenceStatusSnapshot status = stateStore.statusSnapshot();
        String text = displayPolicy.displayText(status);
        if (text.isBlank()) {
            return;
        }

        Font font = minecraft.font;
        int textWidth = font.width(text);
        int panelWidth = Math.max(88, textWidth + 18);
        int panelHeight = font.lineHeight + 10;
        int left = 12;
        int top = graphics.guiHeight() - panelHeight - 18;
        int right = left + panelWidth;
        int bottom = top + panelHeight;

        graphics.fill(left, top, right, bottom, PANEL_BACKGROUND);
        graphics.fill(left, bottom - 1, right, bottom, status.severity() == PresenceSeverity.ERROR ? ERROR_ACCENT : ACTIVE_ACCENT);
        graphics.drawString(font, text, left + 9, top + 5, TEXT_COLOR, true);
    }
}
