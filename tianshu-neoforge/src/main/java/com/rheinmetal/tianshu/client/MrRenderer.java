package com.rheinmetal.tianshu.client;

import com.rheinmetal.tianshu.config.ClientConfig;
import com.rheinmetal.tianshu.function.MR.MrCardSnapshot;
import com.rheinmetal.tianshu.function.MR.MrConstants;
import com.rheinmetal.tianshu.function.MR.MrEngine;
import com.rheinmetal.tianshu.provider.IPlayerStateProvider;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MrRenderer {

    private final MrEngine engine;
    private final Map<String, ItemStack> itemCache = new HashMap<>();
    private final Map<String, MrCardSnapshot> displaySnapshots = new HashMap<>();
    private final Map<String, Entity> visibleEntityCache = new HashMap<>();
    private final Map<String, MrCardSnapshot> targetByUuid = new HashMap<>();
    private final List<MrCardSnapshot> lastFrameSnapshots = new ArrayList<>();
    private final List<MrCardSnapshot> incomingSnapshots = new ArrayList<>();
    private final GuiGeometryBatch geometryBatch = new GuiGeometryBatch();
    private final float[] projectedAnchor = new float[2];
    private int entityCacheRefreshFrames;
    private float projectionForwardX;
    private float projectionForwardY;
    private float projectionForwardZ;
    private float projectionRightX;
    private float projectionRightY;
    private float projectionRightZ;
    private float projectionUpX;
    private float projectionUpY;
    private float projectionUpZ;
    private double projectionCameraX;
    private double projectionCameraY;
    private double projectionCameraZ;
    private double projectionHorizontalTan;
    private double projectionVerticalTan;
    private int projectionScreenW;
    private int projectionScreenH;

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
        incomingSnapshots.clear();
        while ((snap = engine.getOutputQueue().poll()) != null) {
            incomingSnapshots.add(snap);
            receivedNewFrame = true;
        }
        if (receivedNewFrame) {
            lastFrameSnapshots.clear();
            lastFrameSnapshots.addAll(incomingSnapshots);
        }
        engine.tickAnimations(dt.getGameTimeDeltaTicks() / 20.0f);
        refreshVisibleEntityCache(mc, lastFrameSnapshots);
        prepareProjectionFrame(mc);
        for (MrCardSnapshot cachedSnap : lastFrameSnapshots) {
            updateRealtimeAnchor(cachedSnap, mc);
        }
        syncDisplaySnapshots(lastFrameSnapshots);
        geometryBatch.begin();
        for (MrCardSnapshot cachedSnap : lastFrameSnapshots) {
            MrCardSnapshot displaySnap = displaySnapshots.get(cachedSnap.entityUuid);
            if (displaySnap != null) {
                drawCardGeometry(geometryBatch, displaySnap);
            }
        }
        geometryBatch.flush(g);
        for (MrCardSnapshot cachedSnap : lastFrameSnapshots) {
            MrCardSnapshot displaySnap = displaySnapshots.get(cachedSnap.entityUuid);
            if (displaySnap != null) {
                drawCardIcons(g, displaySnap);
            }
        }
        for (MrCardSnapshot cachedSnap : lastFrameSnapshots) {
            MrCardSnapshot displaySnap = displaySnapshots.get(cachedSnap.entityUuid);
            if (displaySnap != null) {
                drawCardText(g, displaySnap, font, mc);
            }
        }
    }

    private void syncDisplaySnapshots(List<MrCardSnapshot> targetSnapshots) {
        targetByUuid.clear();
        for (MrCardSnapshot target : targetSnapshots) {
            if (target.entityUuid == null) continue;
            MrCardSnapshot realtimeTarget = target;
            targetByUuid.put(realtimeTarget.entityUuid, realtimeTarget);
            MrCardSnapshot display = displaySnapshots.get(realtimeTarget.entityUuid);
            if (display == null) {
                displaySnapshots.put(realtimeTarget.entityUuid, realtimeTarget.copy());
            } else if (realtimeTarget.disappearProgress < 1.0f || display.disappearProgress < 1.0f) {
                smoothSnapshot(display, realtimeTarget, 1.0f);
            } else {
                smoothSnapshot(display, realtimeTarget, computeCardFollowFactor(display, realtimeTarget));
            }
        }
        displaySnapshots.keySet().removeIf(uuid -> !targetByUuid.containsKey(uuid));
    }

    private void refreshVisibleEntityCache(Minecraft mc, List<MrCardSnapshot> targetSnapshots) {
        if (mc.level == null || targetSnapshots.isEmpty()) {
            visibleEntityCache.clear();
            entityCacheRefreshFrames = 0;
            return;
        }
        entityCacheRefreshFrames++;
        boolean needsRefresh = entityCacheRefreshFrames >= 5;
        if (!needsRefresh) {
            for (MrCardSnapshot target : targetSnapshots) {
                if (target.entityUuid != null && !visibleEntityCache.containsKey(target.entityUuid)) {
                    needsRefresh = true;
                    break;
                }
            }
        }
        if (!needsRefresh) return;

        visibleEntityCache.clear();
        entityCacheRefreshFrames = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            String uuid = entity.getUUID().toString();
            for (MrCardSnapshot target : targetSnapshots) {
                if (uuid.equals(target.entityUuid)) {
                    visibleEntityCache.put(uuid, entity);
                    break;
                }
            }
        }
    }

    private void prepareProjectionFrame(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Vector3f cameraForward = camera.getLookVector();
        Vector3f cameraUp = camera.rotation().transform(new Vector3f(0.0f, 1.0f, 0.0f));
        projectionCameraX = cameraPos.x;
        projectionCameraY = cameraPos.y;
        projectionCameraZ = cameraPos.z;
        projectionForwardX = cameraForward.x();
        projectionForwardY = cameraForward.y();
        projectionForwardZ = cameraForward.z();
        float forwardLength = (float) Math.sqrt(projectionForwardX * projectionForwardX + projectionForwardY * projectionForwardY + projectionForwardZ * projectionForwardZ);
        if (forwardLength > 1.0E-6f) {
            projectionForwardX /= forwardLength;
            projectionForwardY /= forwardLength;
            projectionForwardZ /= forwardLength;
        }

        projectionUpX = cameraUp.x();
        projectionUpY = cameraUp.y();
        projectionUpZ = cameraUp.z();
        float upLength = (float) Math.sqrt(projectionUpX * projectionUpX + projectionUpY * projectionUpY + projectionUpZ * projectionUpZ);
        if (upLength > 1.0E-6f) {
            projectionUpX /= upLength;
            projectionUpY /= upLength;
            projectionUpZ /= upLength;
        }

        projectionRightX = projectionForwardY * projectionUpZ - projectionForwardZ * projectionUpY;
        projectionRightY = projectionForwardZ * projectionUpX - projectionForwardX * projectionUpZ;
        projectionRightZ = projectionForwardX * projectionUpY - projectionForwardY * projectionUpX;
        float rightLength = (float) Math.sqrt(projectionRightX * projectionRightX + projectionRightY * projectionRightY + projectionRightZ * projectionRightZ);
        if (rightLength > 1.0E-6f) {
            projectionRightX /= rightLength;
            projectionRightY /= rightLength;
            projectionRightZ /= rightLength;
        }

        projectionUpX = projectionRightY * projectionForwardZ - projectionRightZ * projectionForwardY;
        projectionUpY = projectionRightZ * projectionForwardX - projectionRightX * projectionForwardZ;
        projectionUpZ = projectionRightX * projectionForwardY - projectionRightY * projectionForwardX;
        upLength = (float) Math.sqrt(projectionUpX * projectionUpX + projectionUpY * projectionUpY + projectionUpZ * projectionUpZ);
        if (upLength > 1.0E-6f) {
            projectionUpX /= upLength;
            projectionUpY /= upLength;
            projectionUpZ /= upLength;
        }

        projectionScreenW = mc.getWindow().getGuiScaledWidth();
        projectionScreenH = mc.getWindow().getGuiScaledHeight();
        IPlayerStateProvider playerState = TianshuClient.getPlayerStateProvider();
        float currentFov = playerState != null ? playerState.getCurrentDynamicFov() : 70.0f;
        if (currentFov <= 10.0f || currentFov > 180.0f) currentFov = 70.0f;
        double verticalFov = Math.toRadians(currentFov);
        projectionVerticalTan = Math.tan(verticalFov * 0.5);
        projectionHorizontalTan = projectionVerticalTan * ((double) projectionScreenW / Math.max(1, projectionScreenH));
    }

    private boolean updateRealtimeAnchor(MrCardSnapshot target, Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.getCameraEntity() == null) return false;
        if (!target.isOcclusionVisible && target.disappearProgress >= 1.0f) return true;
        float[] anchor = projectCurrentView(mc, target);
        if (anchor == null) return false;
        float anchorX = anchor[0];
        float anchorY = anchor[1];
        float dx = anchorX - target.anchorX;
        float dy = anchorY - target.anchorY;
        target.anchorX = anchorX;
        target.anchorY = anchorY;
        target.jointX += dx;
        target.jointY += dy;
        target.connectorX += dx;
        target.connectorY += dy;
        target.cardX += dx;
        target.cardY += dy;
        return true;
    }

    private float[] projectCurrentView(Minecraft mc, MrCardSnapshot target) {
        if (projectionScreenW <= 0 || projectionScreenH <= 0) return null;
        Entity liveEntity = resolveLiveEntity(mc, target.entityUuid);
        double baseX;
        double baseY;
        double baseZ;
        if (liveEntity != null) {
            baseX = liveEntity.getX();
            baseY = liveEntity.getY();
            baseZ = liveEntity.getZ();
        } else {
            baseX = mc.player.getX() + target.relativeX;
            baseY = mc.player.getY() + target.relativeY;
            baseZ = mc.player.getZ() + target.relativeZ;
        }
        double eyeHeight = liveEntity != null ? liveEntity.getEyeHeight() : target.eyeHeight;
        double headOffset = Math.min(0.18, Math.max(0.04, eyeHeight * 0.08));
        double relX = baseX - projectionCameraX;
        double relY = baseY + eyeHeight + headOffset - projectionCameraY;
        double relZ = baseZ - projectionCameraZ;
        double forward = relX * projectionForwardX + relY * projectionForwardY + relZ * projectionForwardZ;
        if (forward <= 0.05) return null;
        double right = relX * projectionRightX + relY * projectionRightY + relZ * projectionRightZ;
        double viewY = relX * projectionUpX + relY * projectionUpY + relZ * projectionUpZ;
        double ndcX = right / (forward * projectionHorizontalTan);
        double ndcY = viewY / (forward * projectionVerticalTan);
        if (ndcX < -1.08 || ndcX > 1.08 || ndcY < -1.08 || ndcY > 1.08) return null;
        projectedAnchor[0] = (float) ((ndcX * 0.5 + 0.5) * projectionScreenW);
        projectedAnchor[1] = (float) ((0.5 - ndcY * 0.5) * projectionScreenH);
        return projectedAnchor;
    }

    private void smoothSnapshot(MrCardSnapshot display, MrCardSnapshot target, float factor) {
        display.anchorX = target.anchorX;
        display.anchorY = target.anchorY;
        display.jointX = target.jointX;
        display.jointY = target.jointY;
        display.connectorX = lerp(display.connectorX, target.connectorX, factor);
        display.connectorY = lerp(display.connectorY, target.connectorY, factor);
        display.cardX = lerp(display.cardX, target.cardX, factor);
        display.cardY = lerp(display.cardY, target.cardY, factor);
        display.connectorEdge = target.connectorEdge;
        display.connectorEdgeRatio = target.connectorEdgeRatio;
        display.connectorOnTopEdge = target.connectorOnTopEdge;
        display.connectorDirectionX = target.connectorDirectionX;
        display.connectorDirectionY = target.connectorDirectionY;
        display.orthogonalHorizontalFirst = target.orthogonalHorizontalFirst;
        display.cardWidth = lerp(display.cardWidth, target.cardWidth, factor);
        display.cardHeight = lerp(display.cardHeight, target.cardHeight, factor);
        display.scale = lerp(display.scale, target.scale, factor);
        display.alpha = lerp(display.alpha, target.alpha, factor);
        display.distanceFadeAlpha = target.distanceFadeAlpha;
        display.environmentAlphaFactor = target.environmentAlphaFactor;
        float animationFactor = Math.max(0.04f, Math.min(0.35f, factor));
        display.appearProgress = lerp(display.appearProgress, target.appearProgress, animationFactor);
        display.disappearProgress = lerp(display.disappearProgress, target.disappearProgress, animationFactor);
        if (target.appearProgress >= 1.0f && display.appearProgress > 0.995f) display.appearProgress = 1.0f;
        if (target.disappearProgress >= 1.0f && display.disappearProgress > 0.995f) display.disappearProgress = 1.0f;
        if (target.disappearProgress <= 0.0f && display.disappearProgress < 0.005f) display.disappearProgress = 0.0f;
        display.isAlive = target.isAlive;
        display.isHostile = target.isHostile;
        display.isOcclusionVisible = target.isOcclusionVisible;
        display.isFocused = target.isFocused;
        display.isBackground = target.isBackground;
        display.hasMainHandItem = target.hasMainHandItem;
        display.displayName = target.displayName;
        display.entityId = target.entityId;
        display.mainHandItemId = target.mainHandItemId;
        display.distanceIconItemId = target.distanceIconItemId;
        display.attackIconItemId = target.attackIconItemId;
        display.armorIconItemId = target.armorIconItemId;
        display.health = target.health;
        display.maxHealth = target.maxHealth;
        display.distance = target.distance;
        display.attackDamage = target.attackDamage;
        display.armorValue = target.armorValue;
        display.accentColor = target.accentColor;
        display.accentR = target.accentR;
        display.accentG = target.accentG;
        display.accentB = target.accentB;
        display.textAlphaColor = target.textAlphaColor;
        display.accentTextColor = target.accentTextColor;
        display.healthBarBgColor = target.healthBarBgColor;
        display.healthBarColor = target.healthBarColor;
        display.healthBarFillWidth = target.healthBarFillWidth;
        display.healthBarFullWidth = target.healthBarFullWidth;
        display.glitchOffset = target.glitchOffset;
        display.distanceText = target.distanceText;
        display.attackText = target.attackText;
        display.armorText = target.armorText;
        display.focusedDetailText = target.focusedDetailText;
        display.focusedDetailVisibleChars = target.focusedDetailVisibleChars;
        display.focusedDetailOutputFinished = target.focusedDetailOutputFinished;
        display.contentStartX = target.contentStartX;
        display.contentStartY = target.contentStartY;
        display.nameIconX = target.nameIconX;
        display.nameIconY = target.nameIconY;
        display.nameTextX = target.nameTextX;
        display.nameTextY = target.nameTextY;
        display.statsStartX = target.statsStartX;
        display.contentNameEndY = target.contentNameEndY;
        display.contentBarEndY = target.contentBarEndY;
        display.contentStatsY = target.contentStatsY;
        display.distanceIconX = target.distanceIconX;
        display.distanceIconY = target.distanceIconY;
        display.distanceTextX = target.distanceTextX;
        display.attackIconX = target.attackIconX;
        display.attackIconY = target.attackIconY;
        display.atkTextX = target.atkTextX;
        display.armorIconX = target.armorIconX;
        display.armorIconY = target.armorIconY;
        display.defTextX = target.defTextX;
    }

    private float lerp(float from, float to, float factor) {
        return from + (to - from) * factor;
    }

    private float getCardDamping() {
        try {
            return ClientConfig.TACTICAL_MR_CARD_DAMPING.get().floatValue();
        } catch (Exception ignored) {
            return 0.22f;
        }
    }

    private float getCardMinDamping() {
        try {
            return ClientConfig.TACTICAL_MR_CARD_MIN_DAMPING.get().floatValue();
        } catch (Exception ignored) {
            return 0.05f;
        }
    }

    private float getCardMaxDamping() {
        try {
            return ClientConfig.TACTICAL_MR_CARD_MAX_DAMPING.get().floatValue();
        } catch (Exception ignored) {
            return 0.75f;
        }
    }

    private float computeCardFollowFactor(MrCardSnapshot display, MrCardSnapshot target) {
        float baseFactor = getCardDamping();
        float minFactor = getCardMinDamping();
        float maxFactor = getCardMaxDamping();
        if (maxFactor < minFactor) {
            float swap = minFactor;
            minFactor = maxFactor;
            maxFactor = swap;
        }
        baseFactor = Math.max(minFactor, Math.min(maxFactor, baseFactor));
        float dx = target.cardX - display.cardX;
        float dy = target.cardY - display.cardY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float distanceBlend = Math.max(0.0f, Math.min(1.0f, distance / 120.0f));
        float dynamicFactor = minFactor + (baseFactor - minFactor) * distanceBlend;
        if (distance > 120.0f) {
            float catchupBlend = Math.max(0.0f, Math.min(1.0f, (distance - 120.0f) / 120.0f));
            dynamicFactor += (maxFactor - dynamicFactor) * catchupBlend;
        }
        return Math.max(minFactor, Math.min(maxFactor, dynamicFactor));
    }

    private void drawCardGeometry(GuiGeometryBatch batch, MrCardSnapshot s) {
        if (s.alpha <= 0.01f && s.appearProgress <= 0.01f && s.disappearProgress >= 1.0f) return;

        int r = s.accentR;
        int gr = s.accentG;
        int b = s.accentB;

        float ap = s.appearProgress;
        float dp = s.disappearProgress;

        if (dp < 1.0f) {
            drawDisappearCardGeometry(batch, s, r, gr, b, dp);
            return;
        }

        boolean contentVisible = ap > 0.5f;
        drawOriginMarker(batch, s, r, gr, b, Math.min(1.0f, ap * 2.0f));
        drawFocusProgressTether(batch, s, r, gr, b, ap);
        drawTetherLine(batch, s, r, gr, b, ap);

        if (contentVisible) {
            float boxProgress = Math.min(1.0f, (ap - 0.5f) / 0.5f);
            drawCardBackground(batch, s, boxProgress, r, gr, b);
            drawCardContentGeometry(batch, s, boxProgress);
        }
    }

    private void drawCardIcons(GuiGraphics g, MrCardSnapshot s) {
        if (s.alpha <= 0.08f) return;
        float progress = s.disappearProgress < 1.0f ? s.disappearProgress : s.appearProgress;
        if (progress < 0.98f) return;
        float contentScale = Math.max(0.1f, s.scale);
        float iconScale = Math.max(0.1f, s.scale * 0.75f);
        int iconColor = contentColor(s, 0xFFFFFFFF);

        ItemStack distanceStack = resolveItemStack(s.distanceIconItemId);
        if (distanceStack != null) {
            drawScaledItem(g, distanceStack, s.cardX + s.distanceIconX, s.cardY + s.distanceIconY, iconScale, iconColor);
        }

        ItemStack attackStack = resolveItemStack(s.attackIconItemId);
        if (attackStack != null && s.attackText != null) {
            drawScaledItem(g, attackStack, s.cardX + s.attackIconX, s.cardY + s.attackIconY, iconScale, iconColor);
        }

        ItemStack armorStack = resolveItemStack(s.armorIconItemId);
        if (armorStack != null && s.armorText != null) {
            drawScaledItem(g, armorStack, s.cardX + s.armorIconX, s.cardY + s.armorIconY, iconScale, iconColor);
        }
    }

    private void drawCardText(GuiGraphics g, MrCardSnapshot s, Font font, Minecraft mc) {
        if (s.alpha <= 0.01f) return;
        float progress = s.disappearProgress < 1.0f ? s.disappearProgress : s.appearProgress;
        if (progress <= 0.5f) return;
        float boxProgress = Math.min(1.0f, (progress - 0.5f) / 0.5f);
        drawCompressedCardContent(g, s, font, mc, boxProgress);
    }

    private void drawDisappearCardGeometry(GuiGeometryBatch batch, MrCardSnapshot s, int r, int gr, int b, float dp) {
        int outerWidth = Math.max(1, computeOuterLineWidth(s.cardHeight) - 1);
        int innerWidth = 1;

        int alphaOuter = clampAlpha(s.distanceFadeAlpha * s.environmentAlphaFactor * 0.25f);
        int alphaInner = clampAlpha(s.distanceFadeAlpha * s.environmentAlphaFactor * 0.85f);
        int colorOuter = (alphaOuter << 24) | (r << 16) | (gr << 8) | b;
        int colorInner = (alphaInner << 24) | (r << 16) | (gr << 8) | b;

        if (dp > 0.5f) {
            float boxProgress = Math.min(1.0f, (dp - 0.5f) / 0.5f);
            drawCardBackground(batch, s, boxProgress, r, gr, b);
            drawCardContentGeometry(batch, s, boxProgress);
        }

        int ax = (int) s.anchorX;
        int ay = (int) s.anchorY;
        int cx = (int) s.connectorX;
        int cy = (int) s.connectorY;
        int bx;
        int by;
        if (s.orthogonalHorizontalFirst) {
            bx = cx;
            by = ay;
        } else {
            bx = ax;
            by = cy;
        }

        if (dp > 0.3f) {
            drawLine(batch, ax, ay, bx, by, outerWidth, colorOuter);
            drawLine(batch, ax, ay, bx, by, innerWidth, colorInner);

            float t = Math.min(1.0f, (dp - 0.3f) / 0.2f);
            int endX = bx + (int) ((cx - bx) * t);
            int endY = by + (int) ((cy - by) * t);
            drawLine(batch, bx, by, endX, endY, outerWidth, colorOuter);
            drawLine(batch, bx, by, endX, endY, innerWidth, colorInner);
            drawOriginMarker(batch, s, r, gr, b, Math.min(1.0f, dp), s.distanceFadeAlpha);
        } else if (dp > 0.0f) {
            float t = dp / 0.3f;
            int endX = ax + (int) ((bx - ax) * t);
            int endY = ay + (int) ((by - ay) * t);
            drawLine(batch, ax, ay, endX, endY, outerWidth, colorOuter);
            drawLine(batch, ax, ay, endX, endY, innerWidth, colorInner);
            drawOriginMarker(batch, s, r, gr, b, t, s.distanceFadeAlpha);
        }
    }

    private void drawOriginMarker(GuiGeometryBatch batch, MrCardSnapshot s, int r, int gr, int b, float progress) {
        drawOriginMarker(batch, s, r, gr, b, progress, s.alpha);
    }

    private void drawOriginMarker(GuiGeometryBatch batch, MrCardSnapshot s, int r, int gr, int b, float progress, float alphaBase) {
        float p = Math.max(0.0f, Math.min(1.0f, progress));
        int alphaOuter = clampAlpha(alphaBase * 0.25f * p);
        int alphaInner = clampAlpha(alphaBase * 0.85f * p);
        int colorOuter = (alphaOuter << 24) | (r << 16) | (gr << 8) | b;
        int colorInner = (alphaInner << 24) | (r << 16) | (gr << 8) | b;
        int ax = (int) s.anchorX;
        int ay = (int) s.anchorY;
        int outerRadius = computeOriginOuterRadius(s.scale);
        int innerRadius = computeOriginInnerRadius(s.scale);
        drawRectOutline(batch, ax - outerRadius, ay - outerRadius, ax + outerRadius, ay + outerRadius,
                computeOriginLineWidth(s.scale), colorOuter);
        drawRectOutline(batch, ax - innerRadius, ay - innerRadius, ax + innerRadius, ay + innerRadius,
                1, colorInner);
    }

    private void drawTetherLine(GuiGeometryBatch batch, MrCardSnapshot s, int r, int gr, int b, float ap) {
        float lineAlphaBase = Math.max(s.alpha, s.distanceFadeAlpha * Math.min(1.0f, ap / 0.5f));
        int alphaOuter = clampAlpha(lineAlphaBase * s.environmentAlphaFactor * 0.25f);
        int alphaInner = clampAlpha(lineAlphaBase * s.environmentAlphaFactor * 0.85f);

        int colorOuter = (alphaOuter << 24) | (r << 16) | (gr << 8) | b;
        int colorInner = (alphaInner << 24) | (r << 16) | (gr << 8) | b;
        int outerWidth = Math.max(1, computeOuterLineWidth(s.cardHeight) - 1);
        int innerWidth = 1;

        int ax = (int) s.anchorX;
        int ay = (int) s.anchorY;
        int cx = (int) s.connectorX;
        int cy = (int) s.connectorY;
        int bx;
        int by;
        if (s.orthogonalHorizontalFirst) {
            bx = cx;
            by = ay;
        } else {
            bx = ax;
            by = cy;
        }

        if (ap <= 0.3f) {
            float t = ap / 0.3f;
            int endX = ax + (int) ((bx - ax) * t);
            int endY = ay + (int) ((by - ay) * t);
            drawLine(batch, ax, ay, endX, endY, outerWidth, colorOuter);
            drawLine(batch, ax, ay, endX, endY, innerWidth, colorInner);
        } else if (ap <= 0.5f) {
            drawLine(batch, ax, ay, bx, by, outerWidth, colorOuter);
            drawLine(batch, ax, ay, bx, by, innerWidth, colorInner);
            float t = (ap - 0.3f) / 0.2f;
            int endX = bx + (int) ((cx - bx) * t);
            int endY = by + (int) ((cy - by) * t);
            drawLine(batch, bx, by, endX, endY, outerWidth, colorOuter);
            drawLine(batch, bx, by, endX, endY, innerWidth, colorInner);
        } else {
            drawLine(batch, ax, ay, bx, by, outerWidth, colorOuter);
            drawLine(batch, ax, ay, bx, by, innerWidth, colorInner);
            drawLine(batch, bx, by, cx, cy, outerWidth, colorOuter);
            drawLine(batch, bx, by, cx, cy, innerWidth, colorInner);
        }
    }

    private void drawFocusProgressTether(GuiGeometryBatch batch, MrCardSnapshot s, int r, int gr, int b, float ap) {
        if (!s.focusProgressActive || s.focusProgress <= 0.0f || ap <= 0.5f) return;

        float progress = Math.max(0.0f, Math.min(1.0f, s.focusProgress));
        float lineAlphaBase = Math.max(s.alpha, s.distanceFadeAlpha);
        int alphaTrack = clampAlpha(lineAlphaBase * s.environmentAlphaFactor * 0.22f);
        int alphaProgress = clampAlpha(lineAlphaBase * s.environmentAlphaFactor * 0.72f);
        int trackColor = (alphaTrack << 24) | (r << 16) | (gr << 8) | b;
        int progressColor = (alphaProgress << 24) | (r << 16) | (gr << 8) | b;
        int trackWidth = Math.max(3, computeOuterLineWidth(s.cardHeight) + 3);
        int progressWidth = Math.max(2, computeOuterLineWidth(s.cardHeight) + 1);

        int ax = (int) s.anchorX;
        int ay = (int) s.anchorY;
        int cx = (int) s.connectorX;
        int cy = (int) s.connectorY;
        int bx;
        int by;
        if (s.orthogonalHorizontalFirst) {
            bx = cx;
            by = ay;
        } else {
            bx = ax;
            by = cy;
        }

        drawLine(batch, ax, ay, bx, by, trackWidth, trackColor);
        drawLine(batch, bx, by, cx, cy, trackWidth, trackColor);
        drawProgressLine(batch, ax, ay, bx, by, bx, by, cx, cy, progress, progressWidth, progressColor);
    }

    private void drawProgressLine(GuiGeometryBatch batch, int ax, int ay, int bx, int by, int cx1, int cy1, int cx, int cy, float progress, int width, int color) {
        float abLength = distance(ax, ay, bx, by);
        float bcLength = distance(cx1, cy1, cx, cy);
        float totalLength = abLength + bcLength;
        if (totalLength <= 0.01f) return;

        float remaining = Math.max(0.0f, Math.min(1.0f, progress)) * totalLength;
        if (remaining <= 0.0f) return;

        if (abLength > 0.01f) {
            float abDraw = Math.min(remaining, abLength);
            float t = abDraw / abLength;
            int endX = ax + Math.round((bx - ax) * t);
            int endY = ay + Math.round((by - ay) * t);
            drawLine(batch, ax, ay, endX, endY, width, color);
            remaining -= abDraw;
        }

        if (remaining > 0.0f && bcLength > 0.01f) {
            float bcDraw = Math.min(remaining, bcLength);
            float t = bcDraw / bcLength;
            int endX = cx1 + Math.round((cx - cx1) * t);
            int endY = cy1 + Math.round((cy - cy1) * t);
            drawLine(batch, cx1, cy1, endX, endY, width, color);
        }
    }

    private float distance(int x1, int y1, int x2, int y2) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void drawCardBackground(GuiGeometryBatch batch, MrCardSnapshot s, float boxProgress, int r, int gr, int b) {
        float w = s.cardWidth;
        float h = s.cardHeight * boxProgress;
        float x = s.cardX;
        float y = s.connectorOnTopEdge ? s.cardY : s.cardY + s.cardHeight - h;

        int bgAlpha = clampAlpha(s.alpha * s.environmentAlphaFactor * 0.66f);
        int bgColor = (bgAlpha << 24) | 0x101010;
        int borderAlpha = clampAlpha(s.alpha * s.environmentAlphaFactor * 0.95f);
        int borderColor = (borderAlpha << 24) | (r << 16) | (gr << 8) | b;

        int left = (int) x;
        int top = (int) y;
        int right = (int) (x + w);
        int bottom = (int) (y + h);
        batch.fill(left, top, right, bottom, bgColor);
        if (boxProgress > 0.8f) {
            int borderWidth = computeCardBorderWidth(s.scale);
            drawInsetRectOutline(batch, left, top, right, bottom, borderWidth, borderColor);
        }
    }

    private int clampAlpha(float alpha) {
        return Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
    }

    private int computeCardBorderWidth(float scale) {
        return Math.max(1, Math.min(3, Math.round(scale * 2.0f)));
    }

    private void drawCardContentGeometry(GuiGeometryBatch batch, MrCardSnapshot s, float boxProgress) {
        if (boxProgress < 0.98f) return;
        if (s.maxHealth > 0) {
            int barX = (int) (s.cardX + s.contentStartX);
            int barY = (int) (s.cardY + s.contentNameEndY);
            int barW = (int) s.healthBarFullWidth;
            int barFillW = (int) s.healthBarFillWidth;
            int barH = Math.max(1, (int) (MrConstants.CONTENT_BAR_HEIGHT * s.scale));
            batch.fill(barX, barY, barX + barW, barY + barH, s.healthBarBgColor);
            batch.fill(barX, barY, barX + barFillW, barY + barH, s.healthBarColor);
        }
    }

    private void drawCompressedCardContent(GuiGraphics g, MrCardSnapshot s, Font font, Minecraft mc, float boxProgress) {
        if (boxProgress < 0.98f) return;
        drawCardContent(g, s, font, mc);
    }

    private void drawCardContent(GuiGraphics g, MrCardSnapshot s, Font font, Minecraft mc) {
        drawScaledCardContent(g, s, font, mc);
    }

    private int contentColor(MrCardSnapshot s, int color) {
        int alpha = (int) (Math.max(0.0f, Math.min(1.0f, s.alpha * s.environmentAlphaFactor)) * 255.0f) & 0xFF;
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private void drawScaledText(GuiGraphics g, Font font, String text, float x, float y, int color, boolean shadow, float scale) {
        if (text == null || text.isEmpty()) return;
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0f);
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, 0, 0, color, shadow);
        g.pose().popPose();
    }

    private void drawScaledItem(GuiGraphics g, ItemStack stack, float x, float y, float scale, int argb) {
        if (stack == null) return;
        float alpha = ((argb >>> 24) & 0xFF) / 255.0f;
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0f);
        g.pose().scale(scale, scale, 1.0f);
        g.setColor(1.0f, 1.0f, 1.0f, alpha);
        g.renderItem(stack, 0, 0);
        g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        g.pose().popPose();
    }

    private void drawScaledCardContent(GuiGraphics g, MrCardSnapshot s, Font font, Minecraft mc) {
        float contentScale = Math.max(0.1f, s.scale);
        float statsScale = Math.max(0.1f, s.scale * 0.9f);
        if (s.displayName != null) {
            drawScaledText(g, font, s.displayName, s.cardX + s.contentStartX, s.cardY + s.nameTextY, s.accentTextColor, true, contentScale);
        }

        float statsY = s.cardY + s.contentStatsY;

        if (s.distanceText != null) {
            drawScaledText(g, font, s.distanceText, s.cardX + s.distanceTextX, statsY, s.textAlphaColor, false, statsScale);
        }

        if (s.attackText != null) {
            drawScaledText(g, font, s.attackText, s.cardX + s.atkTextX, statsY, s.textAlphaColor, false, statsScale);
        }

        if (s.armorText != null) {
            drawScaledText(g, font, s.armorText, s.cardX + s.defTextX, statsY, s.textAlphaColor, false, statsScale);
        }

        if (s.isFocused && s.focusedDetailText != null && !s.focusedDetailText.isEmpty()) {
            drawFocusedDetailText(g, s, font);
        }
    }

    private void drawFocusedDetailText(GuiGraphics g, MrCardSnapshot s, Font font) {
        int visibleChars = Math.max(0, Math.min(s.focusedDetailVisibleChars, s.focusedDetailText.length()));
        String visibleText = s.focusedDetailText.substring(0, visibleChars);
        List<String> wrappedLines = wrapText(font, visibleText, Math.max(1, (int) ((s.cardWidth - s.contentStartX * 2.0f) / Math.max(0.1f, s.scale))));
        float x = s.cardX + s.contentStartX;
        float y = s.cardY + s.contentStatsY + MrConstants.FONT_LINE_HEIGHT * s.scale + 8.0f * s.scale;
        float contentScale = Math.max(0.1f, s.scale);
        for (String line : wrappedLines) {
            drawScaledText(g, font, line, x, y, s.textAlphaColor, false, contentScale);
            y += Math.max(1.0f, MrConstants.FONT_LINE_HEIGHT * s.scale);
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

    private Entity resolveLiveEntity(Minecraft mc, String entityUuid) {
        if (mc.level == null || entityUuid == null || entityUuid.isEmpty()) return null;
        return visibleEntityCache.get(entityUuid);
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

    private int computeOriginOuterRadius(float scale) {
        float clamped = Math.max(0.1f, Math.min(4.0f, scale));
        return Math.max(1, Math.round(4.0f * clamped));
    }

    private int computeOriginInnerRadius(float scale) {
        float clamped = Math.max(0.1f, Math.min(4.0f, scale));
        return Math.max(1, Math.round(2.0f * clamped));
    }

    private int computeOriginLineWidth(float scale) {
        return Math.max(1, Math.min(3, Math.round(scale * 1.4f)));
    }

    private void drawRectOutline(GuiGeometryBatch batch, int left, int top, int right, int bottom, int width, int color) {
        batch.rectOutline(left, top, right, bottom, width, color);
    }

    private void drawInsetRectOutline(GuiGeometryBatch batch, int left, int top, int right, int bottom, int width, int color) {
        if (width <= 0 || right <= left || bottom <= top) return;
        int clampedWidth = Math.min(width, Math.max(1, Math.min(right - left, bottom - top) / 2));
        batch.fill(left, top, right, top + clampedWidth, color);
        batch.fill(left, bottom - clampedWidth, right, bottom, color);
        batch.fill(left, top + clampedWidth, left + clampedWidth, bottom - clampedWidth, color);
        batch.fill(right - clampedWidth, top + clampedWidth, right, bottom - clampedWidth, color);
    }

    private void drawLine(GuiGeometryBatch batch, int x1, int y1, int x2, int y2, int width, int color) {
        batch.line(x1, y1, x2, y2, width, color);
    }
}
