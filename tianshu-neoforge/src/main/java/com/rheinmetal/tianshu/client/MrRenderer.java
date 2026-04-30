package com.rheinmetal.tianshu.client;

import com.rheinmetal.tianshu.function.MR.MrCardSnapshot;
import com.rheinmetal.tianshu.function.MR.MrConstants;
import com.rheinmetal.tianshu.function.MR.MrEngine;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MrRenderer {

    private final MrEngine engine;
    private final Map<String, ItemStack> itemCache = new HashMap<>();
    private final List<MrCardSnapshot> lastFrameSnapshots = new ArrayList<>();

    public MrRenderer(MrEngine engine) {
        this.engine = engine;
    }

    public void render(GuiGraphics g, DeltaTracker dt) {
        if (engine == null || !engine.isRunning()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Font font = mc.font;

        MrCardSnapshot snap;
        boolean receivedNewFrame = false;
        List<MrCardSnapshot> incomingSnapshots = new ArrayList<>();
        while ((snap = engine.getOutputQueue().poll()) != null) {
            incomingSnapshots.add(snap);
            receivedNewFrame = true;
        }
        if (receivedNewFrame) {
            lastFrameSnapshots.clear();
            lastFrameSnapshots.addAll(incomingSnapshots);
        }
        for (MrCardSnapshot cachedSnap : lastFrameSnapshots) {
            drawCard(g, cachedSnap, font, mc);
        }
    }

    private void drawCard(GuiGraphics g, MrCardSnapshot s, Font font, Minecraft mc) {
        if (s.alpha <= 0.01f) return;

        int r = s.accentR;
        int gr = s.accentG;
        int b = s.accentB;

        float ap = s.appearProgress;
        float dp = s.disappearProgress;

        if (dp < 1.0f) {
            drawDisappearCard(g, s, r, gr, b, dp);
            return;
        }

        drawTetherLine(g, s, r, gr, b, ap);

        if (ap > 0.5f) {
            float boxProgress = Math.min(1.0f, (ap - 0.5f) / 0.5f);
            drawCardBackground(g, s, boxProgress, r, gr, b);
        }

        if (ap > 1.0f) {
            drawCardContent(g, s, font, mc);
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

    private void drawDisappearCard(GuiGraphics g, MrCardSnapshot s, int r, int gr, int b, float dp) {
        float h = s.cardHeight * dp;
        float y = s.cardY + (s.cardHeight - h);

        int bgAlpha = (int) (s.alpha * 0.6f * 255.0f * dp) & 0xFF;
        int bgColor = (bgAlpha << 24);

        if (dp > 0.3f) {
            g.fill((int) s.cardX + s.glitchOffset, (int) y,
                    (int) (s.cardX + s.cardWidth), (int) (y + h), bgColor);
        }

        if (dp > 0.5f) {
            int alphaOuter = (int) (s.alpha * 0.3f * 255.0f * dp) & 0xFF;
            int alphaInner = (int) (s.alpha * 0.9f * 255.0f * dp) & 0xFF;
            int colorOuter = (alphaOuter << 24) | (r << 16) | (gr << 8) | b;
            int colorInner = (alphaInner << 24) | (r << 16) | (gr << 8) | b;

            drawLine(g, (int) s.cardX, (int) y, (int) (s.cardX + s.cardWidth), (int) y,
                    MrConstants.NEON_WIDTH_OUTER, colorOuter);
            drawLine(g, (int) s.cardX, (int) y, (int) (s.cardX + s.cardWidth), (int) y,
                    MrConstants.NEON_WIDTH_INNER, colorInner);
        }

        if (dp > 0.6f) {
            int alphaOuter = (int) (s.alpha * 0.3f * 255.0f * dp) & 0xFF;
            int alphaInner = (int) (s.alpha * 0.9f * 255.0f * dp) & 0xFF;
            int colorOuter = (alphaOuter << 24) | (r << 16) | (gr << 8) | b;
            int colorInner = (alphaInner << 24) | (r << 16) | (gr << 8) | b;

            int cx = (int) (s.cardX + s.cardWidth * 0.5f);
            int cy = (int) (y + h);
            drawLine(g, cx, cy, (int) s.jointX, (int) s.jointY,
                    MrConstants.NEON_WIDTH_OUTER, colorOuter);
            drawLine(g, cx, cy, (int) s.jointX, (int) s.jointY,
                    MrConstants.NEON_WIDTH_INNER, colorInner);
        }

        if (dp > 0.8f) {
            int alphaOuter = (int) (s.alpha * 0.3f * 255.0f * dp) & 0xFF;
            int alphaInner = (int) (s.alpha * 0.9f * 255.0f * dp) & 0xFF;
            int colorOuter = (alphaOuter << 24) | (r << 16) | (gr << 8) | b;
            int colorInner = (alphaInner << 24) | (r << 16) | (gr << 8) | b;

            drawLine(g, (int) s.jointX, (int) s.jointY, (int) s.anchorX, (int) s.anchorY,
                    MrConstants.NEON_WIDTH_OUTER, colorOuter);
            drawLine(g, (int) s.jointX, (int) s.jointY, (int) s.anchorX, (int) s.anchorY,
                    MrConstants.NEON_WIDTH_INNER, colorInner);
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

        g.fill((int) x, (int) y, (int) (x + w), (int) (y + h), (bgAlpha << 24));

        if (boxProgress > 0.8f) {
            int corner = MrConstants.CUT_CORNER_SIZE;

            g.fill((int) x, (int) y, (int) x + corner, (int) y + corner, (bgAlpha << 24));
            g.fill((int) (x + w - corner), (int) y, (int) (x + w), (int) y + corner, (bgAlpha << 24));
            g.fill((int) x, (int) (y + h - corner), (int) x + corner, (int) (y + h), (bgAlpha << 24));
            g.fill((int) (x + w - corner), (int) (y + h - corner), (int) (x + w), (int) (y + h), (bgAlpha << 24));

            int borderAlpha = (int) (s.alpha * 0.8f * 255.0f) & 0xFF;
            int borderColor = (borderAlpha << 24) | (r << 16) | (gr << 8) | b;

            drawLine(g, (int) (x + corner), (int) y, (int) (x + w - corner), (int) y, MrConstants.NEON_WIDTH_INNER, borderColor);
            drawLine(g, (int) (x + corner), (int) (y + h), (int) (x + w - corner), (int) (y + h), MrConstants.NEON_WIDTH_INNER, borderColor);
            drawLine(g, (int) x, (int) (y + corner), (int) x, (int) (y + h - corner), MrConstants.NEON_WIDTH_INNER, borderColor);
            drawLine(g, (int) (x + w), (int) (y + corner), (int) (x + w), (int) (y + h - corner), MrConstants.NEON_WIDTH_INNER, borderColor);
        }
    }

    private void drawCardContent(GuiGraphics g, MrCardSnapshot s, Font font, Minecraft mc) {
        int ix = (int) s.contentStartX;
        int iy = (int) s.contentStartY;

        if (s.displayName != null) {
            g.drawString(font, s.displayName, (int) (s.cardX + ix), (int) (s.cardY + iy), s.accentTextColor, true);
        }

        if (s.maxHealth > 0) {
            int barX = (int) (s.cardX + ix);
            int barY = (int) (s.cardY + s.contentNameEndY);
            g.fill(barX, barY, barX + (int) s.healthBarFullWidth, barY + (int) MrConstants.CONTENT_BAR_HEIGHT, s.healthBarBgColor);
            g.fill(barX, barY, barX + (int) s.healthBarFillWidth, barY + (int) MrConstants.CONTENT_BAR_HEIGHT, s.healthBarColor);
        }

        int statsX = (int) (s.cardX + ix);
        int statsY = (int) (s.cardY + s.contentStatsY);

        if (s.distanceText != null) {
            g.drawString(font, s.distanceText, statsX, statsY, s.textAlphaColor, false);
        }

        if (s.attackText != null) {
            if (s.hasMainHandItem) {
                ItemStack weaponStack = resolveItemStack(s.mainHandItemId);
                if (weaponStack != null) {
                    g.renderItem(weaponStack, (int) (s.cardX + s.weaponIconX), (int) (s.cardY + s.weaponIconY));
                }
            }
            g.drawString(font, s.attackText, (int) (s.cardX + s.atkTextX), statsY, s.textAlphaColor, false);
        }

        if (s.armorText != null) {
            g.drawString(font, s.armorText, (int) (s.cardX + s.defTextX), statsY, s.textAlphaColor, false);
        }
    }

    private ItemStack resolveItemStack(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;

        ItemStack cached = itemCache.get(itemId);
        if (cached != null) return cached;

        try {
            ResourceLocation loc = ResourceLocation.parse(itemId);
            Item item = BuiltInRegistries.ITEM.getOptional(loc).orElse(null);
            if (item != null) {
                ItemStack stack = new ItemStack(item);
                itemCache.put(itemId, stack);
                return stack;
            }
        } catch (Exception ignored) {
        }
        return null;
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
