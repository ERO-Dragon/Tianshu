package com.rheinmetal.tianshu.function.presence.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudSurface;

/**
 * Frame-level drawing shapes shared by the presence HUD elements.
 *
 * <p>They exist so the icon and the partner marker draw rings with the same segment count and the same alpha
 * clamping instead of each keeping its own copy — a ring that looks different depending on who drew it would be
 * a bug nobody can see but everybody notices. Every shape is built from the platform surface's one primitive
 * ({@code fill}), so these stay product-side maths rather than renderer code.</p>
 */
final class PresenceHudShapes {
    private static final int RING_SEGMENTS = 64;
    private static final int RING_SEGMENT_LIMIT = 512;
    private static final int DISC_BANDS_MIN = 3;
    private static final int DISC_BANDS_LIMIT = 64;

    private PresenceHudShapes() {
    }

    /**
     * Draws an arc as short straight segments; {@code coverage} is a fraction of a full turn and {@code rotation}
     * is the angle the arc starts at.
     *
     * <p>The segment count follows the circumference (about one segment per pixel, with {@link #RING_SEGMENTS} as the
     * floor and 512 as the ceiling), because a fixed 64-segment ring reads as a dotted line once it gets large.
     * Neighbouring segments overlap by several pixels at that density, so the alpha is divided by the overlap ratio:
     * a denser ring keeps the brightness of the old one instead of stacking into a solid blob.</p>
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

    /**
     * A soft disc drawn as horizontal bands; the band count follows the pixel height (one band per pixel, 3..64) so a
     * large glow is a gradient instead of nine visible steps, while a small dot still costs three fills.
     */
    static void drawDisc(HudSurface surface, float centreX, float centreY, float radius, int color, float opacity) {
        int alpha = clampColor(opacity * ((color >>> 24) & 0xFF));
        if (alpha <= 0 || radius <= 0.0F) {
            return;
        }
        int argb = (alpha << 24) | (color & 0x00FFFFFF);
        int bands = Math.min(DISC_BANDS_LIMIT, Math.max(DISC_BANDS_MIN, (int) Math.ceil(radius * 2.0F)));
        float bandHeight = Math.max(1.0F, radius * 2.0F / bands);
        for (int band = 0; band < bands; band++) {
            float normalized = ((band + 0.5F) / bands) * 2.0F - 1.0F;
            float horizontal = radius * (float) Math.sqrt(Math.max(0.0F, 1.0F - normalized * normalized));
            float y = centreY + normalized * radius;
            surface.fill(centreX - horizontal, y, centreX + horizontal + 1.0F, y + bandHeight, argb);
        }
    }

    static int clampColor(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    static float fract(float value) {
        return value - (float) Math.floor(value);
    }

    static int mixColor(int first, int second, float progress) {
        int r = Math.round(((first >>> 16) & 0xFF) + (((second >>> 16) & 0xFF) - ((first >>> 16) & 0xFF)) * progress);
        int g = Math.round(((first >>> 8) & 0xFF) + (((second >>> 8) & 0xFF) - ((first >>> 8) & 0xFF)) * progress);
        int b = Math.round((first & 0xFF) + ((second & 0xFF) - (first & 0xFF)) * progress);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
