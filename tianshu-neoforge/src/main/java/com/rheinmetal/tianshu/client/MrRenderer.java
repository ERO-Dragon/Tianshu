package com.rheinmetal.tianshu.client;

import com.rheinmetal.tianshu.function.MR.MrCardSnapshot;
import com.rheinmetal.tianshu.function.MR.MrConstants;
import com.rheinmetal.tianshu.function.MR.MrEngine;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            drawDisappearCard(g, s, font, mc, r, gr, b, dp);
            return;
        }

        drawOriginMarker(g, s, r, gr, b, Math.min(1.0f, ap * 2.0f));
        drawTetherLine(g, s, r, gr, b, ap);

        if (ap > 0.5f) {
            float boxProgress = Math.min(1.0f, (ap - 0.5f) / 0.5f);
            drawCardBackground(g, s, boxProgress, r, gr, b);
        }

        if (ap > 1.0f) {
            drawCardContent(g, s, font, mc);
        }
    }

    private void drawDisappearCard(GuiGraphics g, MrCardSnapshot s, Font font, Minecraft mc, int r, int gr, int b, float dp) {
        int outerWidth = computeOuterLineWidth(s.cardHeight);
        int innerWidth = computeInnerLineWidth(s.cardHeight);

        int alphaOuter = (int) (s.alpha * 0.3f * 255.0f) & 0xFF;
        int alphaInner = (int) (s.alpha * 0.9f * 255.0f) & 0xFF;
        int colorOuter = (alphaOuter << 24) | (r << 16) | (gr << 8) | b;
        int colorInner = (alphaInner << 24) | (r << 16) | (gr << 8) | b;

        if (dp > 0.5f) {
            float boxProgress = Math.min(1.0f, (dp - 0.5f) / 0.5f);
            drawCardBackground(g, s, boxProgress, r, gr, b);
            drawCompressedCardContent(g, s, font, mc, boxProgress);
        }

        int ax = (int) s.anchorX;
        int ay = (int) s.anchorY;
        int bx = (int) s.jointX;
        int by = (int) s.jointY;
        int cx = (int) s.connectorX;
        int cy = (int) s.connectorY;

        if (dp > 0.3f) {
            drawLine(g, ax, ay, bx, by, outerWidth, colorOuter);
            drawLine(g, ax, ay, bx, by, innerWidth, colorInner);

            float t = Math.min(1.0f, (dp - 0.3f) / 0.2f);
            int endX = bx + (int) ((cx - bx) * t);
            int endY = by + (int) ((cy - by) * t);
            drawLine(g, bx, by, endX, endY, outerWidth, colorOuter);
            drawLine(g, bx, by, endX, endY, innerWidth, colorInner);
            drawOriginMarker(g, s, r, gr, b, Math.min(1.0f, dp));
        } else if (dp > 0.0f) {
            float t = dp / 0.3f;
            int endX = ax + (int) ((bx - ax) * t);
            int endY = ay + (int) ((by - ay) * t);
            drawLine(g, ax, ay, endX, endY, outerWidth, colorOuter);
            drawLine(g, ax, ay, endX, endY, innerWidth, colorInner);
            drawOriginMarker(g, s, r, gr, b, t);
        }
    }

    private void drawOriginMarker(GuiGraphics g, MrCardSnapshot s, int r, int gr, int b, float progress) {
        float p = Math.max(0.0f, Math.min(1.0f, progress));
        int alphaOuter = (int) (s.alpha * 0.25f * 255.0f * p) & 0xFF;
        int alphaInner = (int) (s.alpha * 0.85f * 255.0f * p) & 0xFF;
        int colorOuter = (alphaOuter << 24) | (r << 16) | (gr << 8) | b;
        int colorInner = (alphaInner << 24) | (r << 16) | (gr << 8) | b;
        int ax = (int) s.anchorX;
        int ay = (int) s.anchorY;
        int outerRadius = computeOriginOuterRadius(s.cardHeight);
        int innerRadius = computeOriginInnerRadius(s.cardHeight);
        drawRectOutline(g, ax - outerRadius, ay - outerRadius, ax + outerRadius, ay + outerRadius,
                computeOuterLineWidth(s.cardHeight), colorOuter);
        drawRectOutline(g, ax - innerRadius, ay - innerRadius, ax + innerRadius, ay + innerRadius,
                computeInnerLineWidth(s.cardHeight), colorInner);
    }

    private void drawTetherLine(GuiGraphics g, MrCardSnapshot s, int r, int gr, int b, float ap) {
        int alphaOuter = (int) (s.alpha * 0.3f * 255.0f) & 0xFF;
        int alphaInner = (int) (s.alpha * 0.9f * 255.0f) & 0xFF;

        int colorOuter = (alphaOuter << 24) | (r << 16) | (gr << 8) | b;
        int colorInner = (alphaInner << 24) | (r << 16) | (gr << 8) | b;
        int outerWidth = computeOuterLineWidth(s.cardHeight);
        int innerWidth = computeInnerLineWidth(s.cardHeight);

        int ax = (int) s.anchorX;
        int ay = (int) s.anchorY;
        int bx = (int) s.jointX;
        int by = (int) s.jointY;

        if (ap <= 0.3f) {
            float t = ap / 0.3f;
            int endX = ax + (int) ((bx - ax) * t);
            int endY = ay + (int) ((by - ay) * t);
            drawLine(g, ax, ay, endX, endY, outerWidth, colorOuter);
            drawLine(g, ax, ay, endX, endY, innerWidth, colorInner);
        } else if (ap <= 0.5f) {
            drawLine(g, ax, ay, bx, by, outerWidth, colorOuter);
            drawLine(g, ax, ay, bx, by, innerWidth, colorInner);

            float t = (ap - 0.3f) / 0.2f;
            int cx = (int) s.connectorX;
            int cy = (int) s.connectorY;
            int endX = bx + (int) ((cx - bx) * t);
            int endY = by + (int) ((cy - by) * t);
            drawLine(g, bx, by, endX, endY, outerWidth, colorOuter);
            drawLine(g, bx, by, endX, endY, innerWidth, colorInner);
        } else {
            drawLine(g, ax, ay, bx, by, outerWidth, colorOuter);
            drawLine(g, ax, ay, bx, by, innerWidth, colorInner);
            drawLine(g, bx, by, (int) s.connectorX, (int) s.connectorY, outerWidth, colorOuter);
            drawLine(g, bx, by, (int) s.connectorX, (int) s.connectorY, innerWidth, colorInner);
        }
    }

    private void drawCardBackground(GuiGraphics g, MrCardSnapshot s, float boxProgress, int r, int gr, int b) {
        float w = s.cardWidth;
        float h = s.cardHeight * boxProgress;
        float x = s.cardX;
        float y = s.connectorOnTopEdge ? s.cardY : s.cardY + s.cardHeight - h;

        int bgAlpha = (int) (s.alpha * 0.6f * 255.0f) & 0xFF;
        int bgCenterAlpha = (int) (s.alpha * 0.32f * 255.0f) & 0xFF;
        int bgOuter = (bgAlpha << 24) | ((r / 4) << 16) | ((gr / 4) << 8) | (b / 4);
        int bgCenter = (bgCenterAlpha << 24) | ((r / 2) << 16) | ((gr / 2) << 8) | (b / 2);

        int midY = (int) (y + h * 0.5f);
        g.fillGradient((int) x, (int) y, (int) (x + w), midY, bgOuter, bgCenter);
        g.fillGradient((int) x, midY, (int) (x + w), (int) (y + h), bgCenter, bgOuter);

        if (boxProgress > 0.8f) {
            int corner = computeCutCornerSize(w, h);
            int alphaOuter = (int) (s.alpha * 0.28f * 255.0f) & 0xFF;
            int alphaInner = (int) (s.alpha * 0.9f * 255.0f) & 0xFF;
            int colorOuter = (alphaOuter << 24) | (r << 16) | (gr << 8) | b;
            int colorInner = (alphaInner << 24) | (r << 16) | (gr << 8) | b;
            drawCutCornerBorder(g, (int) x, (int) y, (int) (x + w), (int) (y + h), corner,
                    computeOuterLineWidth(h), colorOuter);
            drawCutCornerBorder(g, (int) x, (int) y, (int) (x + w), (int) (y + h), corner,
                    computeInnerLineWidth(h), colorInner);
        }
    }

    private void drawCompressedCardContent(GuiGraphics g, MrCardSnapshot s, Font font, Minecraft mc, float boxProgress) {
        float p = Math.max(0.0f, Math.min(1.0f, boxProgress));
        if (p <= 0.01f) return;
        int clipX1 = (int) s.cardX;
        int clipX2 = (int) (s.cardX + s.cardWidth);
        int clipY1;
        int clipY2;
        float pivotY;
        if (s.connectorOnTopEdge) {
            clipY1 = (int) s.cardY;
            clipY2 = (int) (s.cardY + s.cardHeight * p);
            pivotY = s.cardY;
        } else {
            clipY1 = (int) (s.cardY + s.cardHeight * (1.0f - p));
            clipY2 = (int) (s.cardY + s.cardHeight);
            pivotY = s.cardY + s.cardHeight;
        }
        g.enableScissor(clipX1, clipY1, clipX2, clipY2);
        g.pose().pushPose();
        g.pose().translate(0.0f, pivotY, 0.0f);
        g.pose().scale(1.0f, p, 1.0f);
        g.pose().translate(0.0f, -pivotY, 0.0f);
        drawCardContent(g, s, font, mc);
        g.pose().popPose();
        g.disableScissor();
    }

    private void drawCardContent(GuiGraphics g, MrCardSnapshot s, Font font, Minecraft mc) {
        int ix = (int) s.contentStartX;

        if (s.displayName != null) {
            drawEntityFaceIcon(g, mc, s, (int) (s.cardX + s.nameIconX), (int) (s.cardY + s.nameIconY));
            g.drawString(font, s.displayName, (int) (s.cardX + s.nameTextX), (int) (s.cardY + s.nameTextY), s.accentTextColor, true);
        }

        if (s.maxHealth > 0) {
            int barX = (int) (s.cardX + ix);
            int barY = (int) (s.cardY + s.contentNameEndY);
            g.fill(barX, barY, barX + (int) s.healthBarFullWidth, barY + (int) MrConstants.CONTENT_BAR_HEIGHT, s.healthBarBgColor);
            g.fill(barX, barY, barX + (int) s.healthBarFillWidth, barY + (int) MrConstants.CONTENT_BAR_HEIGHT, s.healthBarColor);
        }

        int statsY = (int) (s.cardY + s.contentStatsY);

        ItemStack distanceStack = resolveItemStack(s.distanceIconItemId);
        if (distanceStack != null) {
            g.renderItem(distanceStack, (int) (s.cardX + s.distanceIconX), (int) (s.cardY + s.distanceIconY));
        }
        if (s.distanceText != null) {
            g.drawString(font, s.distanceText, (int) (s.cardX + s.distanceTextX), statsY, s.textAlphaColor, false);
        }

        ItemStack attackStack = resolveItemStack(s.attackIconItemId);
        if (attackStack != null) {
            g.renderItem(attackStack, (int) (s.cardX + s.attackIconX), (int) (s.cardY + s.attackIconY));
        }
        g.drawString(font, s.attackText != null ? s.attackText : "0", (int) (s.cardX + s.atkTextX), statsY, s.textAlphaColor, false);

        ItemStack armorStack = resolveItemStack(s.armorIconItemId);
        if (armorStack != null) {
            g.renderItem(armorStack, (int) (s.cardX + s.armorIconX), (int) (s.cardY + s.armorIconY));
        }
        g.drawString(font, s.armorText != null ? s.armorText : "0", (int) (s.cardX + s.defTextX), statsY, s.textAlphaColor, false);

        if (s.isFocused && s.focusedDetailText != null && !s.focusedDetailText.isEmpty()) {
            drawFocusedDetailText(g, s, font);
        }
    }

    private void drawFocusedDetailText(GuiGraphics g, MrCardSnapshot s, Font font) {
        int visibleChars = Math.max(0, Math.min(s.focusedDetailVisibleChars, s.focusedDetailText.length()));
        String visibleText = s.focusedDetailText.substring(0, visibleChars);
        List<String> wrappedLines = wrapText(font, visibleText, Math.max(1, (int) (s.cardWidth - s.contentStartX * 2.0f)));
        int x = (int) (s.cardX + s.contentStartX);
        int y = (int) (s.cardY + s.contentStatsY + MrConstants.FONT_LINE_HEIGHT + 8.0f);
        for (String line : wrappedLines) {
            g.drawString(font, line, x, y, s.textAlphaColor, false);
            y += MrConstants.FONT_LINE_HEIGHT;
        }
    }

    private List<String> wrapText(Font font, String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        String[] explicitLines = text.split("\n", -1);
        for (String explicitLine : explicitLines) {
            appendWrappedLine(font, explicitLine, maxWidth, result);
        }
        if (result.isEmpty()) result.add("");
        return result;
    }

    private void appendWrappedLine(Font font, String line, int maxWidth, List<String> result) {
        if (line.isEmpty()) {
            result.add("");
            return;
        }
        int start = 0;
        while (start < line.length()) {
            int end = line.length();
            while (end > start + 1 && font.width(line.substring(start, end)) > maxWidth) {
                end--;
            }
            result.add(line.substring(start, end));
            start = end;
        }
    }

    private void drawEntityFaceIcon(GuiGraphics g, Minecraft mc, MrCardSnapshot s, int x, int y) {
        ItemStack skullStack = resolveItemStack(resolveEntitySkullItemId(s.entityId));
        if (skullStack != null) {
            g.renderItem(skullStack, x, y);
            return;
        }

        ResourceLocation texture = resolveEntityTexture(mc, s);
        if (texture == null) {
            ItemStack fallback = resolveItemStack("minecraft:player_head");
            if (fallback != null) g.renderItem(fallback, x, y);
            return;
        }
        g.blit(texture, x, y, 8.0f, 8.0f, 16, 16, 64, 64);
    }

    private String resolveEntitySkullItemId(String entityId) {
        if ("minecraft:zombie".equals(entityId)) return "minecraft:zombie_head";
        if ("minecraft:skeleton".equals(entityId)) return "minecraft:skeleton_skull";
        if ("minecraft:wither_skeleton".equals(entityId)) return "minecraft:wither_skeleton_skull";
        if ("minecraft:creeper".equals(entityId)) return "minecraft:creeper_head";
        if ("minecraft:piglin".equals(entityId)) return "minecraft:piglin_head";
        if ("minecraft:ender_dragon".equals(entityId)) return "minecraft:dragon_head";
        return null;
    }

    private ResourceLocation resolveEntityTexture(Minecraft mc, MrCardSnapshot s) {
        if (mc.level == null || s == null) return null;
        Entity liveEntity = resolveLiveEntity(mc, s.entityUuid);
        if (liveEntity != null) {
            try {
                return getEntityTexture(mc, liveEntity);
            } catch (Exception ignored) {
            }
        }
        return resolveEntityTypeTexture(mc, s.entityId);
    }

    private Entity resolveLiveEntity(Minecraft mc, String entityUuid) {
        if (mc.level == null || entityUuid == null || entityUuid.isEmpty()) return null;
        try {
            UUID uuid = UUID.fromString(entityUuid);
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (uuid.equals(entity.getUUID())) return entity;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private ResourceLocation resolveEntityTypeTexture(Minecraft mc, String entityId) {
        if (mc.level == null || entityId == null || entityId.isEmpty()) return null;
        try {
            ResourceLocation loc = ResourceLocation.parse(entityId);
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(loc).orElse(null);
            if (type == null) return null;
            Entity entity = type.create(mc.level);
            if (entity == null) return null;
            return getEntityTexture(mc, entity);
        } catch (Exception ignored) {
            return null;
        }
    }

    private <T extends Entity> ResourceLocation getEntityTexture(Minecraft mc, T entity) {
        EntityRenderer<? super T> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
        return renderer.getTextureLocation(entity);
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

    private int computeOuterLineWidth(float height) {
        float scaled = height * MrConstants.NEON_OUTER_WIDTH_HEIGHT_RATIO;
        float clamped = Math.max(MrConstants.NEON_OUTER_WIDTH_MIN, Math.min(MrConstants.NEON_OUTER_WIDTH_MAX, scaled));
        return Math.max(1, Math.round(clamped));
    }

    private int computeInnerLineWidth(float height) {
        float scaled = height * MrConstants.NEON_INNER_WIDTH_HEIGHT_RATIO;
        float clamped = Math.max(MrConstants.NEON_INNER_WIDTH_MIN, Math.min(MrConstants.NEON_INNER_WIDTH_MAX, scaled));
        return Math.max(1, Math.round(clamped));
    }

    private int computeOriginOuterRadius(float height) {
        float scaled = height * MrConstants.ORIGIN_MARKER_OUTER_RADIUS_HEIGHT_RATIO;
        float clamped = Math.max(MrConstants.ORIGIN_MARKER_OUTER_RADIUS_MIN, Math.min(MrConstants.ORIGIN_MARKER_OUTER_RADIUS_MAX, scaled));
        return Math.max(1, Math.round(clamped));
    }

    private int computeOriginInnerRadius(float height) {
        float scaled = height * MrConstants.ORIGIN_MARKER_INNER_RADIUS_HEIGHT_RATIO;
        float clamped = Math.max(MrConstants.ORIGIN_MARKER_INNER_RADIUS_MIN, Math.min(MrConstants.ORIGIN_MARKER_INNER_RADIUS_MAX, scaled));
        return Math.max(1, Math.round(clamped));
    }

    private int computeCutCornerSize(float width, float height) {
        float scaled = height * MrConstants.CUT_CORNER_HEIGHT_RATIO;
        float clamped = Math.max(MrConstants.CUT_CORNER_MIN_SIZE, Math.min(MrConstants.CUT_CORNER_MAX_SIZE, scaled));
        return Math.max(1, Math.min((int) clamped, (int) Math.min(width, height) / 3));
    }

    private void drawCutCornerBorder(GuiGraphics g, int left, int top, int right, int bottom, int corner, int width, int color) {
        drawLine(g, left + corner, top, right - corner, top, width, color);
        drawLine(g, right - corner, top, right, top + corner, width, color);
        drawLine(g, right, top + corner, right, bottom - corner, width, color);
        drawLine(g, right, bottom - corner, right - corner, bottom, width, color);
        drawLine(g, right - corner, bottom, left + corner, bottom, width, color);
        drawLine(g, left + corner, bottom, left, bottom - corner, width, color);
        drawLine(g, left, bottom - corner, left, top + corner, width, color);
        drawLine(g, left, top + corner, left + corner, top, width, color);
    }

    private void drawRectOutline(GuiGraphics g, int left, int top, int right, int bottom, int width, int color) {
        drawLine(g, left, top, right, top, width, color);
        drawLine(g, right, top, right, bottom, width, color);
        drawLine(g, right, bottom, left, bottom, width, color);
        drawLine(g, left, bottom, left, top, width, color);
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
