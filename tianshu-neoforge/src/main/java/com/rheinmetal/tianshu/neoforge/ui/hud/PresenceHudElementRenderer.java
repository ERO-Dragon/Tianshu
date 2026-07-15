package com.rheinmetal.tianshu.neoforge.ui.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public interface PresenceHudElementRenderer {
    PresenceHudElementType type();

    void render(GuiGraphics graphics, Font font, PresenceHudElementFrame frame);
}
