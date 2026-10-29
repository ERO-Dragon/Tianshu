package com.rheinmetal.tianshu.neoforge.ui.hud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

/**
 * Frame-level drawing shapes shared by the presence HUD elements.
 *
 * <p>They exist so the icon and the partner marker draw rings with the same segment count and the same alpha
 * clamping instead of each keeping its own copy — a ring that looks different depending on who drew it would be
 * a bug nobody can see but everybody notices.</p>
 */
final class PresenceHudShapes {
    private static final int RING_SEGMENTS = 64;

    private PresenceHudShapes() {
    }

    /**
     * Draws an arc as short straight segments; {@code coverage} is a fraction of a full turn and {@code rotation}
     * is the angle the arc starts at.
     */
    static void drawRing(
            GuiGraphics graphics,
            float centerX,
            float centerY,
            float radius,
            float thickness,
            int color,
            float opacity,
            float rotation,
            float coverage
    ) {
        int alpha = clampColor(opacity * ((color >>> 24) & 0xFF));
        if (alpha <= 0) {
            return;
        }
        int argb = (alpha << 24) | (color & 0x00FFFFFF);
        int halfThickness = Math.max(1, Math.round(thickness / 2.0F));
        int segments = Math.max(1, Math.round(RING_SEGMENTS * Math.min(1.0F, Math.max(0.0F, coverage))));
        for (int i = 0; i < segments; i++) {
            float angle = rotation + (float) (i * Math.PI * 2.0 / RING_SEGMENTS);
            int x = Math.round(centerX + (float) Math.cos(angle) * radius);
            int y = Math.round(centerY + (float) Math.sin(angle) * radius);
            graphics.fill(
                    RenderType.guiOverlay(),
                    x - halfThickness,
                    y - halfThickness,
                    x + halfThickness + 1,
                    y + halfThickness + 1,
                    argb
            );
        }
    }

    static int clampColor(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    static float fract(float value) {
        return value - (float) Math.floor(value);
    }
}