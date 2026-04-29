package com.rheinmetal.tianshu.client;

import com.rheinmetal.tianshu.function.MR.MrCardSnapshot;
import com.rheinmetal.tianshu.function.MR.MrConstants;
import com.rheinmetal.tianshu.function.MR.MrEngine;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class MrRenderer {

    private final MrEngine engine;

    public MrRenderer(MrEngine engine) {
        this.engine = engine;
    }

    public void render(GuiGraphics g, DeltaTracker dt) {
        if (engine == null || !engine.isRunning()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Font font = mc.font;

        MrCardSnapshot snap;
        while ((snap = engine.getOutputQueue().poll()) != null) {
            drawCard(g, snap, font);
        }
    }

    private void drawCard(GuiGraphics g, MrCardSnapshot s, Font font) {
        if (s.alpha <= 0.01f) return;

        int accentR = (s.accentColor >> 16) & 0xFF;
        int accentG = (s.accentColor >> 8) & 0xFF;
        int accentB = s.accentColor & 0xFF;

        if (s.isGrayscale) {
            int gray = (accentR + accentG + accentB) / 3;
            accentR = gray;
            accentG = gray;
            accentB = gray;
        }

        float ap = s.appearProgress;

        drawTetherLine(g, s, accentR, accentG, accentB, ap);

        if (ap > 0.5f) {
            float boxProgress = Math.min(1.0f, (ap - 0.5f) / 0.5f);
            drawCardBackground(g, s, boxProgress, accentR, accentG, accentB);
        }

        if (ap > 1.0f) {
            drawCardContent(g, s, font, accentR, accentG, accentB);
        }

        if (s.isBackground) {
            int maskColor = MrConstants.COLOR_BACKGROUND_MASK;
            float maskAlpha = (float) ((maskColor >> 24) & 0xFF) / 255.0f * s.alpha;
            int ma = (int) (maskAlpha * 255.0f) & 0xFF;
            g.fill(
                    (int) s.cardX, (int) s.cardY,
                    (int) (s.cardX + s.cardWidth), (int) (s.cardY + s.cardHeight),
                    (ma << 24)
            );
        }
    }

    private void drawTetherLine(GuiGraphics g, MrCardSnapshot s, int r, int gr, int b, float ap) {
        int alphaOuter = (int) (s.alpha * 0.3f * 255.0f) & 0xFF;
        int alphaInner = (int) (s.alpha * 0.9f * 255.0f) & 0xFF;

        int colorOuter = (alphaOuter << 24) | (r << 16) | (gr << 8) | b;
        int colorInner = (alphaInner << 24) | (r << 16) | (gr << 8) | b;

        int ax = (int) s.anchorX;
        int ay = (int) s.anchorY;
        int bx = (int) s.jointX;
        int by = (int) s.jointY;

        if (ap <= 0.3f) {
            float t = ap / 0.3f;
            int endX = ax + (int) ((bx - ax) * t);
            int endY = ay + (int) ((by - ay) * t);
            drawLine(g, ax, ay, endX, endY, MrConstants.NEON_WIDTH_OUTER, colorOuter);
            drawLine(g, ax, ay, endX, endY, MrConstants.NEON_WIDTH_INNER, colorInner);
        } else if (ap <= 0.5f) {
            drawLine(g, ax, ay, bx, by, MrConstants.NEON_WIDTH_OUTER, colorOuter);
            drawLine(g, ax, ay, bx, by, MrConstants.NEON_WIDTH_INNER, colorInner);

            float t = (ap - 0.3f) / 0.2f;
            int cx = (int) s.cardX;
            int cy = (int) s.cardY;
            int endX = bx + (int) ((cx - bx) * t);
            int endY = by + (int) ((cy - by) * t);
            drawLine(g, bx, by, endX, endY, MrConstants.NEON_WIDTH_OUTER, colorOuter);
            drawLine(g, bx, by, endX, endY, MrConstants.NEON_WIDTH_INNER, colorInner);
        } else {
            drawLine(g, ax, ay, bx, by, MrConstants.NEON_WIDTH_OUTER, colorOuter);
            drawLine(g, ax, ay, bx, by, MrConstants.NEON_WIDTH_INNER, colorInner);
            drawLine(g, bx, by, (int) s.cardX, (int) s.cardY, MrConstants.NEON_WIDTH_OUTER, colorOuter);
            drawLine(g, bx, by, (int) s.cardX, (int) s.cardY, MrConstants.NEON_WIDTH_INNER, colorInner);
        }
    }

    private void drawCardBackground(GuiGraphics g, MrCardSnapshot s, float boxProgress, int r, int gr, int b) {
        float w = s.cardWidth * boxProgress;
        float h = s.cardHeight * boxProgress;
        float x = s.cardX + (s.cardWidth - w) * 0.5f;
        float y = s.cardY + (s.cardHeight - h) * 0.5f;

        int bgAlpha = (int) (s.alpha * 0.6f * 255.0f) & 0xFF;
        int bgColor = (bgAlpha << 24) | 0x000000;

        g.fill((int) x, (int) y, (int) (x + w), (int) (y + h), bgColor);

        if (boxProgress > 0.8f) {
            int corner = MrConstants.CUT_CORNER_SIZE;
            int screenBg = (bgAlpha << 24);

            g.fill((int) x, (int) y, (int) x + corner, (int) y + corner, screenBg);
            g.fill((int) (x + w - corner), (int) y, (int) (x + w), (int) y + corner, screenBg);
            g.fill((int) x, (int) (y + h - corner), (int) x + corner, (int) (y + h), screenBg);
            g.fill((int) (x + w - corner), (int) (y + h - corner), (int) (x + w), (int) (y + h), screenBg);

            int borderAlpha = (int) (s.alpha * 0.8f * 255.0f) & 0xFF;
            int borderColor = (borderAlpha << 24) | (r << 16) | (gr << 8) | b;

            drawLine(g, (int) (x + corner), (int) y, (int) (x + w - corner), (int) y, MrConstants.NEON_WIDTH_INNER, borderColor);
            drawLine(g, (int) (x + corner), (int) (y + h), (int) (x + w - corner), (int) (y + h), MrConstants.NEON_WIDTH_INNER, borderColor);
            drawLine(g, (int) x, (int) (y + corner), (int) x, (int) (y + h - corner), MrConstants.NEON_WIDTH_INNER, borderColor);
            drawLine(g, (int) (x + w), (int) (y + corner), (int) (x + w), (int) (y + h - corner), MrConstants.NEON_WIDTH_INNER, borderColor);
        }
    }

    private void drawCardContent(GuiGraphics g, MrCardSnapshot s, Font font, int r, int gr, int b) {
        int textAlpha = (int) (s.alpha * 255.0f) & 0xFF;
        int textColor = (textAlpha << 24) | 0xFFFFFF;
        int accentTextColor = (textAlpha << 24) | (r << 16) | (gr << 8) | b;

        float contentX = s.cardX + 4;
        float contentY = s.cardY + 3;

        if (s.displayName != null) {
            g.drawString(font, s.displayName, (int) contentX, (int) contentY, accentTextColor, true);
            contentY += font.lineHeight + 2;
        }

        if (s.maxHealth > 0) {
            float barWidth = s.cardWidth - 8;
            float healthRatio = s.health / s.maxHealth;

            int barBgColor = (textAlpha << 24) | 0x333333;
            g.fill((int) contentX, (int) contentY, (int) (contentX + barWidth), (int) contentY + 4, barBgColor);

            int healthR = (int) (255 * (1 - healthRatio));
            int healthG = (int) (255 * healthRatio);
            int healthColor = (textAlpha << 24) | (healthR << 16) | (healthG << 8);
            g.fill((int) contentX, (int) contentY, (int) (contentX + barWidth * healthRatio), (int) contentY + 4, healthColor);

            contentY += 7;
        }

        String distanceText = String.format("%.0fm", s.distance);
        g.drawString(font, distanceText, (int) contentX, (int) contentY, textColor, false);

        float statsX = contentX + 40;

        if (s.attackDamage > 0) {
            String atkText = "\u2694 " + String.format("%.0f", s.attackDamage);
            g.drawString(font, atkText, (int) statsX, (int) contentY, textColor, false);
            statsX += 35;
        }

        if (s.armorValue > 0) {
            String defText = "\u26E8 " + String.format("%.0f", s.armorValue);
            g.drawString(font, defText, (int) statsX, (int) contentY, textColor, false);
        }
    }

    private void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int width, int color) {
        if (x1 == x2 && y1 == y2) return;

        if (x1 == x2) {
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            int hw = width / 2;
            g.fill(x1 - hw, minY, x1 + hw + 1, maxY + 1, color);
        } else if (y1 == y2) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int hh = width / 2;
            g.fill(minX, y1 - hh, maxX + 1, y1 + hh + 1, color);
        } else {
            int dx = Math.abs(x2 - x1);
            int dy = Math.abs(y2 - y1);
            int steps = Math.max(dx, dy);
            for (int i = 0; i <= steps; i++) {
                float t = (float) i / steps;
                int px = x1 + (int) ((x2 - x1) * t);
                int py = y1 + (int) ((y2 - y1) * t);
                int hw = width / 2;
                g.fill(px - hw, py, px + hw + 1, py + 1, color);
            }
        }
    }
}
