package com.rheinmetal.tianshu.client.gui.presence.hud.element;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public interface PresenceHudElementRenderer {
    PresenceHudElementType type();

    void render(GuiGraphics graphics, Font font, PresenceHudElementFrame frame);
}
