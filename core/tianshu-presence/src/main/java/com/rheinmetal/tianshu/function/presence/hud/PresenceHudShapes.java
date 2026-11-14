package com.rheinmetal.tianshu.function.presence.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudSurface;

/**
 * The partner marker's ring.
 *
 * <p>The main icon is drawn entirely by its fragment programs; this helper only survives for the partner marker,
 * whose icon is a texture and whose lease ring is a 2D arc. The ring density follows the circumference so a large
 * marker does not read as a dotted line, and the alpha is divided by the overlap ratio so the denser ring keeps its
 * brightness.</p>
 */
final class PresenceHudShapes {
    private static final int RING_SEGMENTS = 64;
    private static final int RING_SEGMENT_LIMIT = 512;

    private PresenceHudShapes() {
    }

    /**
     * Draws an arc as short straight segments; {@code coverage} is a fraction of a full turn and {@code rotation}
     * is the angle the arc starts at.
     *
     * <p>The segment count follows the circumference (about one segment per pixel, with {@link #RING_SEGMENTS} as the
     * floor and {@link #RING_SEGMENT_LIMIT} as the ceiling), because a fixed 64-segment ring reads as a dotted line
     * once it gets large. Neighbouring segments overlap by several pixels at that density, so the alpha is divided by
     * the overlap ratio: a denser ring keeps the brightness of the old one instead of stacking into a solid blob.</p>
     */
    static void drawRing(
            HudSurface surface,
            float centreX,
            float centreY,
            float radius,
            float thickness,
            int color,
            float opacity,
            float rotation,
            float coverage
    ) {
        float clampedCoverage = Math.max(0.0F, Math.min(1.0F, coverage));
        int fullSegments = Math.min(RING_SEGMENT_LIMIT, Math.max(RING_SEGMENTS,
                Math.round((float) (Math.PI * 2.0 * Math.max(0.001F, radius)))));
        int segments = Math.max(1, Math.round(fullSegments * clampedCoverage));
        float halfThickness = Math.max(1.0F, thickness / 2.0F);
        float step = (float) (Math.PI * 2.0 * Math.max(0.001F, radius)) / fullSegments;
        float overlap = Math.max(1.0F, (halfThickness * 2.0F + 1.0F) / Math.max(0.001F, step));
        int alpha = clampColor(opacity * ((color >>> 24) & 0xFF) / overlap);
        if (alpha <= 0) {
            return;
        }
        int argb = (alpha << 24) | (color & 0x00FFFFFF);
        for (int i = 0; i < segments; i++) {
            float angle = rotation + (float) (i * Math.PI * 2.0 / fullSegments);
            float x = centreX + (float) Math.cos(angle) * radius;
            float y = centreY + (float) Math.sin(angle) * radius;
            surface.fill(x - halfThickness, y - halfThickness, x + halfThickness + 1.0F, y + halfThickness + 1.0F, argb);
        }
    }

    static int clampColor(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }
}
