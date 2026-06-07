package com.rheinmetal.tianshu.client.gui.auxilium;

import net.minecraft.client.gui.GuiGraphics;

public final class AXHudLogoRenderer {
    private static final int SIZE = 42;

    public int reservedHeight() {
        return SIZE + 12;
    }

    public void render(GuiGraphics graphics, int centerX, int bottomY, float partialTick) {
        int radius = SIZE / 2;
        int left = centerX - radius;
        int top = bottomY - SIZE;
        graphics.fill(left + 12, top + 4, left + 30, top + 6, 0x99A7F3FF);
        graphics.fill(left + 8, top + 10, left + 34, top + 13, 0xAA7ED7C1);
        graphics.fill(left + 5, top + 17, left + 37, top + 21, 0xCCF5D06F);
        graphics.fill(left + 8, top + 26, left + 34, top + 29, 0xAA7ED7C1);
        graphics.fill(left + 12, top + 34, left + 30, top + 36, 0x99A7F3FF);
    }
}
