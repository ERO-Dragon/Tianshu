package com.rheinmetal.tianshu.function.MR;

import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.provider.IRenderContextProvider;
import com.rheinmetal.tianshu.snapshot.MatrixSnapshot;
import com.rheinmetal.tianshu.snapshot.NearbyEntityData;
import com.rheinmetal.tianshu.snapshot.PositionData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;


public class MrEngine {

    private static final int PROJECTION_MISS_GRACE_TICKS = 6;
    private static final int LINE_OF_SIGHT_MISS_GRACE_TICKS = 6;

    private final MrStateMachine stateMachine = new MrStateMachine();
    private final ConcurrentLinkedQueue<MrCardSnapshot> outputQueue = new ConcurrentLinkedQueue<>();

    private final IEnvironmentAwarenessProvider environmentProvider;
    private final IRenderContextProvider renderContextProvider;

    private final Map<String, TrackedCard> activeCards = new LinkedHashMap<>();
    private boolean initialStaggerDone = false;
    private int initialStaggerIndex = 0;

    private float scanningTimer = 0.0f;
    private float aimWarmupTimer = 0.0f;
    private float gazeTimer = 0.0f;
    private float focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
    private int debugTickCounter = 0;
    private String lastGazeUuid = null;

    private volatile boolean running = false;

    private static void debugLog(String msg) {
        try {
            Path logDir = Paths.get("logs");
            Files.createDirectories(logDir);
            Path logPath = logDir.resolve("mr_debug.txt");
            String line = "[" + System.currentTimeMillis() + "] " + msg + "\n";
            Files.write(logPath, line.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[MR] 写入 MR 排错日志失败: " + e.getMessage());
        }
    }

    private static final class TargetGeometry {
        final float cardX;
        final float cardY;
        final float connectorDirectionX;
        final float connectorDirectionY;
        final boolean connectorOnTopEdge;
        final float connectorEdgeRatio;

        TargetGeometry(float cardX, float cardY, float connectorDirectionX, float connectorDirectionY, boolean connectorOnTopEdge, float connectorEdgeRatio) {
            this.cardX = cardX;
            this.cardY = cardY;
            this.connectorDirectionX = connectorDirectionX;
            this.connectorDirectionY = connectorDirectionY;
            this.connectorOnTopEdge = connectorOnTopEdge;
            this.connectorEdgeRatio = connectorEdgeRatio;
        }
    }

    private static final class TrackedCard {
        final String uuid;
        MrAnimationController animation;
        MrWhipLayout layout;
        MrCardSnapshot lastSnapshot;
        float visualScaleFactor = 1.0f;
        float visualAlphaFactor = 1.0f;
        float lastSeenAnchorX = 0.0f;
        float lastSeenAnchorY = 0.0f;
        boolean hasLastSeenAnchor = false;
        boolean wasLineOfSight = true;
        String focusedDetailText = "";
        float focusedDetailVisibleChars = 0.0f;
        boolean focusedDetailOutputFinished = false;
        float layoutOffsetX = 0.0f;
        float layoutOffsetY = 0.0f;
        float lastLayoutDirectionX = 0.0f;
        float lastLayoutDirectionY = 0.0f;
        boolean hasLastLayoutDirection = false;
        int projectionMissTicks = 0;
        int lineOfSightMissTicks = 0;

        TrackedCard(String uuid) {
            this.uuid = uuid;
            this.animation = new MrAnimationController();
            this.layout = new MrWhipLayout();
        }
    }

    public MrEngine(
            IEnvironmentAwarenessProvider environmentProvider,
            IRenderContextProvider renderContextProvider
    ) {
        this.environmentProvider = environmentProvider;
        this.renderContextProvider = renderContextProvider;
    }

    public MrStateMachine getStateMachine() {
        return stateMachine;
    }

    public ConcurrentLinkedQueue<MrCardSnapshot> getOutputQueue() {
        return outputQueue;
    }

    public boolean isRunning() {
        return running;
    }

    public double getRequiredRadius() {
        return stateMachine.isActive() ? MrConstants.MR_RANGE : 0.0;
    }

    public void start() {
        stateMachine.transitionToScanning();
        running = true;
        scanningTimer = 0.0f;
        aimWarmupTimer = 0.0f;
        gazeTimer = 0.0f;
        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
        lastGazeUuid = null;
        initialStaggerDone = false;
        initialStaggerIndex = 0;
        debugTickCounter = 0;
        debugLog("start state=" + stateMachine.getState() + " running=" + running + " range=" + MrConstants.MR_RANGE);
    }

    public void stop() {
        stateMachine.transitionToSilent();
        running = false;
        activeCards.clear();
        outputQueue.clear();
        scanningTimer = 0.0f;
        aimWarmupTimer = 0.0f;
        gazeTimer = 0.0f;
        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
        lastGazeUuid = null;
        initialStaggerDone = false;
        initialStaggerIndex = 0;
        debugTickCounter = 0;
        debugLog("stop state=" + stateMachine.getState() + " running=" + running);
    }

    public void tick(PositionData playerPos) {
        tick(playerPos, MrConstants.TICK_DURATION);
    }

    public void tick(PositionData playerPos, float deltaTime) {
        if (!stateMachine.isActive() || playerPos == null) {
            if (debugTickCounter == 0) {
                debugLog("tick skipped active=" + stateMachine.isActive() + " playerPos=" + (playerPos != null));
            }
            outputQueue.clear();
            return;
        }

        if (deltaTime <= 0.0f) {
            deltaTime = MrConstants.TICK_DURATION;
        }

        List<NearbyEntityData> entities = new ArrayList<>(environmentProvider.getNearbyEntities(MrConstants.MR_RANGE));
        debugTickCounter++;
        boolean shouldLogDebug = debugTickCounter <= 10 || debugTickCounter % 20 == 0;
        if (entities.isEmpty() && shouldLogDebug) {
            debugLog("tick=" + debugTickCounter + " active=true entities=0 state=" + stateMachine.getState() + " player=" + playerPos.getX() + "," + playerPos.getY() + "," + playerPos.getZ());
        }

        entities.sort(Comparator
                .comparing(NearbyEntityData::isHostile).reversed()
                .thenComparingDouble(NearbyEntityData::getDistance));

        if (entities.size() > MrConstants.MAX_CARDS) {
            entities = new ArrayList<>(entities.subList(0, MrConstants.MAX_CARDS));
        }

        MatrixSnapshot projMatrix = renderContextProvider.getProjectionMatrix();
        MatrixSnapshot mvMatrix = renderContextProvider.getModelViewMatrix();
        int screenW = renderContextProvider.getScreenWidth();
        int screenH = renderContextProvider.getScreenHeight();

        if (screenW <= 0 || screenH <= 0) {
            if (shouldLogDebug) {
                debugLog("tick=" + debugTickCounter + " active=true invalid_screen entities=" + entities.size() + " screen=" + screenW + "x" + screenH);
            }
            return;
        }

        boolean matrixProjectionAvailable = projMatrix != null && mvMatrix != null;
        float[] projData = matrixProjectionAvailable ? projMatrix.getData() : null;
        float[] mvData = matrixProjectionAvailable ? mvMatrix.getData() : null;

        Set<String> currentUuids = new HashSet<>();
        List<MrCardSnapshot> frameSnapshots = new ArrayList<>();
        int projectionFailedCount = 0;
        int projectionGraceCount = 0;
        int lineOfSightMissingCount = 0;
        int lineOfSightGraceCount = 0;
        int disappearingCount = 0;

        for (NearbyEntityData entity : entities) {
            String uuid = entity.getUuid();

            TrackedCard tracked = activeCards.get(uuid);

            double entityWorldX = playerPos.getX() + entity.getRelativeX();
            double entityWorldY = playerPos.getY() + entity.getRelativeY() + entity.getBoundingHeight() + 0.2;
            double entityWorldZ = playerPos.getZ() + entity.getRelativeZ();

            float[] screenPos = null;
            if (matrixProjectionAvailable) {
                screenPos = MrProjector.project(
                        entityWorldX, entityWorldY, entityWorldZ,
                        mvData, projData,
                        screenW, screenH
                );
            }
            if (screenPos == null) {
                screenPos = projectFromPlayerView(playerPos, entity, screenW, screenH);
            }

            if (screenPos == null) {
                projectionFailedCount++;
                if (tracked != null) {
                    tracked.projectionMissTicks++;
                    if (tracked.projectionMissTicks <= PROJECTION_MISS_GRACE_TICKS && tracked.lastSnapshot != null) {
                        projectionGraceCount++;
                        MrCardSnapshot retainedSnapshot = tracked.lastSnapshot.copy();
                        retainedSnapshot.alpha = Math.max(0.0f, Math.min(1.0f,
                                retainedSnapshot.distanceFadeAlpha * tracked.animation.getAnimationAlpha()
                                        * tracked.visualAlphaFactor));
                        tracked.animation.tick(deltaTime);
                        frameSnapshots.add(retainedSnapshot);
                        currentUuids.add(uuid);
                    } else {
                        tracked.animation.triggerDisappear();
                    }
                }
                continue;
            }

            float anchorX = screenPos[0];
            float anchorY = screenPos[1];
            boolean lineOfSight = entity.isLineOfSight();
            boolean entityAlive = entity.getHealth() > 0.0f;

            if (tracked == null) {
                tracked = new TrackedCard(uuid);
                float staggerDelay = 0.0f;
                if (!initialStaggerDone) {
                    staggerDelay = initialStaggerIndex * MrConstants.STAGGER_DELAY;
                    initialStaggerIndex++;
                }
                tracked.animation.setStaggerDelay(staggerDelay);
                activeCards.put(uuid, tracked);
            }

            currentUuids.add(uuid);
            tracked.projectionMissTicks = 0;

            if (entityAlive) {
                if (lineOfSight) {
                    tracked.lineOfSightMissTicks = 0;
                    tracked.wasLineOfSight = true;
                } else {
                    lineOfSightMissingCount++;
                    tracked.lineOfSightMissTicks++;
                    if (tracked.lineOfSightMissTicks <= LINE_OF_SIGHT_MISS_GRACE_TICKS) {
                        lineOfSightGraceCount++;
                    }
                    tracked.wasLineOfSight = false;
                }
                tracked.lastSeenAnchorX = anchorX;
                tracked.lastSeenAnchorY = anchorY;
                tracked.hasLastSeenAnchor = true;
                tracked.animation.recoverAppear();
            } else {
                if (!tracked.hasLastSeenAnchor) {
                    tracked.lastSeenAnchorX = anchorX;
                    tracked.lastSeenAnchorY = anchorY;
                    tracked.hasLastSeenAnchor = true;
                }
                anchorX = tracked.lastSeenAnchorX;
                anchorY = tracked.lastSeenAnchorY;
                tracked.animation.triggerDisappear();
            }

            if (tracked.animation.isFullyDead()) {
                tracked.animation = new MrAnimationController();
                tracked.layout = new MrWhipLayout();
            }

            tracked.animation.tick(deltaTime);

            if (tracked.animation.isFullyDead()) {
                activeCards.remove(uuid);
                continue;
            }

            if (!tracked.animation.isVisible()) {
                disappearingCount++;
            }

            float dist = (float) entity.getDistance();
            float baseCardW = computeBaseCardWidth(screenW);
            float baseCardH = computeBaseCardHeight(screenH);
            float scale = (float) (MrConstants.BASE_DISTANCE / Math.max(dist, 1.0));
            scale = Math.max(0.4f, Math.min(1.5f, scale));

            boolean isFocused = stateMachine.isFocusing()
                    && uuid.equals(stateMachine.getFocusedEntityUuid());
            boolean isBackground = stateMachine.isFocusing() && !isFocused;
            float targetVisualScaleFactor = isFocused
                    ? MrConstants.FOCUS_SCALE
                    : isBackground ? MrConstants.BACKGROUND_SCALE : 1.0f;
            float targetVisualAlphaFactor = isBackground ? MrConstants.BACKGROUND_ALPHA_FACTOR : 1.0f;
            tracked.visualScaleFactor = smoothApproach(tracked.visualScaleFactor, targetVisualScaleFactor, deltaTime);
            tracked.visualAlphaFactor = smoothApproach(tracked.visualAlphaFactor, targetVisualAlphaFactor, deltaTime);
            scale *= tracked.visualScaleFactor;
            scale = clampFocusedScale(scale, baseCardW, baseCardH, screenW, screenH);
            float cardW = baseCardW * scale;
            boolean hasMainHand = entity.getMainHandItemId() != null && !entity.getMainHandItemId().isEmpty();
            boolean isFocusedThisFrame = isFocused;
            if (isFocusedThisFrame) {
                updateFocusedDetailText(tracked, entity, deltaTime);
            } else if (!isBackground) {
                resetFocusedDetailText(tracked);
            }
            float cardH = computeCardHeight(baseCardH, scale, cardW, isFocusedThisFrame, tracked.focusedDetailText);

            TargetGeometry targetGeometry = computeTargetGeometry(anchorX, anchorY, scale, cardW, cardH, screenW, screenH);
            float targetCardX = targetGeometry.cardX;
            float targetCardY = targetGeometry.cardY;

            MrWhipLayout.LayoutResult layoutResult = tracked.layout.compute(
                    anchorX, anchorY,
                    targetCardX, targetCardY,
                    scale, cardW, cardH,
                    screenW, screenH, deltaTime
            );

            float distanceFactor = 1.0f - (dist / (float) MrConstants.MR_RANGE) * MrConstants.DISTANCE_ALPHA_FACTOR;
            distanceFactor = Math.max(MrConstants.MIN_DISTANCE_ALPHA, Math.min(1.0f, distanceFactor));
            float distanceAlpha = isFocused ? MrConstants.BASE_ALPHA : MrConstants.BASE_ALPHA * distanceFactor;
            float animAlpha = tracked.animation.getAnimationAlpha();
            float alpha = distanceAlpha * animAlpha * tracked.visualAlphaFactor;

            MrCardSnapshot snap = new MrCardSnapshot();
            snap.anchorX = anchorX;
            snap.anchorY = anchorY;
            snap.jointX = layoutResult.jointX;
            snap.jointY = layoutResult.jointY;
            snap.cardX = layoutResult.cardX;
            snap.cardY = layoutResult.cardY;
            snap.cardWidth = cardW;
            snap.cardHeight = cardH;
            snap.connectorEdgeRatio = targetGeometry.connectorEdgeRatio;
            snap.connectorOnTopEdge = targetGeometry.connectorOnTopEdge;
            snap.connectorDirectionX = targetGeometry.connectorDirectionX;
            snap.connectorDirectionY = targetGeometry.connectorDirectionY;
            syncConnectorAfterCardMove(snap);
            snap.scale = scale;
            snap.alpha = Math.max(0.0f, Math.min(1.0f, alpha));
            snap.distanceFadeAlpha = distanceAlpha;
            snap.appearProgress = tracked.animation.getAppearProgress();
            snap.disappearProgress = tracked.animation.getDisappearProgress();
            snap.isAlive = entityAlive;
            snap.isHostile = entity.isHostile();
            snap.isLineOfSight = lineOfSight;
            snap.isFocused = isFocused;
            snap.isBackground = isBackground;
            snap.hasMainHandItem = hasMainHand;
            snap.displayName = entity.getDisplayName();
            snap.entityId = entity.getEntityId();
            snap.mainHandItemId = entity.getMainHandItemId();
            snap.entityUuid = uuid;
            snap.health = entity.getHealth();
            snap.maxHealth = entity.getMaxHealth();
            snap.distance = dist;
            snap.attackDamage = entity.getAttackDamage();
            snap.armorValue = entity.getArmorValue();
            snap.focusedDetailText = tracked.focusedDetailText;
            snap.focusedDetailVisibleChars = Math.round(tracked.focusedDetailVisibleChars);
            snap.focusedDetailOutputFinished = tracked.focusedDetailOutputFinished;
            precomputeVisuals(snap);

            tracked.lastSnapshot = snap;
            frameSnapshots.add(snap);
        }

        if (shouldLogDebug) {
            debugLog("tick=" + debugTickCounter + " active=true state=" + stateMachine.getState() + " entities=" + entities.size() + " projected=" + frameSnapshots.size() + " projection_failed=" + projectionFailedCount + " projection_grace=" + projectionGraceCount + " los_missing=" + lineOfSightMissingCount + " los_grace=" + lineOfSightGraceCount + " disappearing=" + disappearingCount + " tracked=" + activeCards.size() + " screen=" + screenW + "x" + screenH);
        }

        if (stateMachine.isScanning()) {
            scanningTimer += deltaTime;
            if (scanningTimer >= MrConstants.SCANNING_WARMUP && !frameSnapshots.isEmpty()) {
                String crosshairUuid = environmentProvider.getCrosshairTargetEntityUuid();

                boolean foundInFrame = false;
                if (crosshairUuid != null) {
                    for (MrCardSnapshot s : frameSnapshots) {
                        if (crosshairUuid.equals(s.entityUuid)) {
                            foundInFrame = true;
                            break;
                        }
                    }
                }

                if (foundInFrame) {
                    updateGazeTimers(crosshairUuid, deltaTime);
                    if (gazeTimer >= MrConstants.GAZE_FOCUS_DURATION) {
                        stateMachine.transitionToFocusing(crosshairUuid);
                        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
                        aimWarmupTimer = 0.0f;
                        gazeTimer = 0.0f;
                        lastGazeUuid = null;
                    }
                } else {
                    lastGazeUuid = null;
                    aimWarmupTimer = 0.0f;
                    gazeTimer = Math.max(0.0f, gazeTimer - deltaTime);
                }
            }
        }

        if (!initialStaggerDone && scanningTimer >= MrConstants.SCANNING_WARMUP) {
            initialStaggerDone = true;
        }

        if (stateMachine.isFocusing()) {
            String focusedUuid = stateMachine.getFocusedEntityUuid();
            boolean focusedVisible = false;
            for (MrCardSnapshot snap : frameSnapshots) {
                if (snap.entityUuid.equals(focusedUuid)) {
                    focusedVisible = true;
                    break;
                }
            }

            if (!focusedVisible) {
                transitionFocusBackToScanning();
            } else {
                String crosshairUuid = environmentProvider.getCrosshairTargetEntityUuid();
                if (focusedUuid.equals(crosshairUuid)) {
                    focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
                    aimWarmupTimer = 0.0f;
                    gazeTimer = 0.0f;
                    lastGazeUuid = null;
                } else if (crosshairUuid != null && hasSnapshot(frameSnapshots, crosshairUuid)) {
                    transitionFocusBackToScanningForGazeSwitch(crosshairUuid);
                } else if (isFocusedDetailOutputFinished(frameSnapshots, focusedUuid)) {
                    focusExitCountdown -= deltaTime;
                    aimWarmupTimer = 0.0f;
                    gazeTimer = 0.0f;
                    lastGazeUuid = null;
                    if (focusExitCountdown <= 0.0f) {
                        transitionFocusBackToScanning();
                    }
                }

                if (stateMachine.isFocusing()) {
                    focusedUuid = stateMachine.getFocusedEntityUuid();
                    for (MrCardSnapshot snap : frameSnapshots) {
                        snap.isFocused = snap.entityUuid.equals(focusedUuid);
                        snap.isBackground = !snap.isFocused;
                    }
                }
            }
        }

        Iterator<Map.Entry<String, TrackedCard>> it = activeCards.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TrackedCard> entry = it.next();
            String uuid = entry.getKey();
            if (!currentUuids.contains(uuid)) {
                TrackedCard tracked = entry.getValue();
                tracked.animation.triggerDisappear();
                tracked.animation.tick(deltaTime);
                if (tracked.animation.isFullyDead()) {
                    it.remove();
                } else if (tracked.lastSnapshot != null) {
                    MrCardSnapshot fadingSnapshot = tracked.lastSnapshot.copy();
                    float anchorX = tracked.hasLastSeenAnchor ? tracked.lastSeenAnchorX : fadingSnapshot.anchorX;
                    float anchorY = tracked.hasLastSeenAnchor ? tracked.lastSeenAnchorY : fadingSnapshot.anchorY;
                    TargetGeometry targetGeometry = computeTargetGeometry(
                            anchorX, anchorY,
                            fadingSnapshot.scale,
                            fadingSnapshot.cardWidth,
                            fadingSnapshot.cardHeight,
                            screenW, screenH
                    );
                    MrWhipLayout.LayoutResult layoutResult = tracked.layout.compute(
                            anchorX, anchorY,
                            targetGeometry.cardX, targetGeometry.cardY,
                            fadingSnapshot.scale,
                            fadingSnapshot.cardWidth,
                            fadingSnapshot.cardHeight,
                            screenW, screenH, deltaTime
                    );
                    float fadeAlpha = Math.max(0.0f, Math.min(1.0f,
                            fadingSnapshot.distanceFadeAlpha * tracked.animation.getAnimationAlpha()
                                    * tracked.visualAlphaFactor));
                    fadingSnapshot.anchorX = anchorX;
                    fadingSnapshot.anchorY = anchorY;
                    fadingSnapshot.jointX = layoutResult.jointX;
                    fadingSnapshot.jointY = layoutResult.jointY;
                    fadingSnapshot.cardX = layoutResult.cardX;
                    fadingSnapshot.cardY = layoutResult.cardY;
                    updateConnectorPoint(fadingSnapshot, anchorX, screenW);
                    fadingSnapshot.alpha = fadeAlpha;
                    fadingSnapshot.appearProgress = tracked.animation.getAppearProgress();
                    fadingSnapshot.disappearProgress = tracked.animation.getDisappearProgress();
                    fadingSnapshot.isLineOfSight = false;
                    precomputeVisuals(fadingSnapshot);

                    tracked.lastSnapshot = fadingSnapshot;
                    frameSnapshots.add(fadingSnapshot);
                }
            }
        }

        applyPreLayoutDirectionSearch(frameSnapshots, deltaTime, screenW, screenH);
        for (MrCardSnapshot snap : frameSnapshots) {
            syncConnectorAfterCardMove(snap);
            precomputeVisuals(snap);
        }

        outputQueue.clear();
        for (MrCardSnapshot snap : frameSnapshots) {
            outputQueue.offer(snap);
        }
    }

    private float[] projectFromPlayerView(PositionData playerPos, NearbyEntityData entity, int screenW, int screenH) {
        double verticalFov = Math.toRadians(70.0);
        double horizontalFov = 2.0 * Math.atan(Math.tan(verticalFov * 0.5) * ((double) screenW / Math.max(1, screenH)));
        double relativeYawRad = Math.toRadians(entity.getHorizontalAngle());
        double ndcX = Math.tan(relativeYawRad) / Math.tan(horizontalFov * 0.5);
        double horizontalDistance = Math.max(0.1, Math.sqrt(entity.getRelativeX() * entity.getRelativeX() + entity.getRelativeZ() * entity.getRelativeZ()));
        double targetHeightFromEye = entity.getRelativeY() + entity.getBoundingHeight() + 0.2 - 1.62;
        double pitchRad = Math.toRadians(playerPos.getPitch());
        double targetPitchRad = Math.atan2(targetHeightFromEye, horizontalDistance);
        double relativePitchRad = targetPitchRad + pitchRad;
        double ndcY = Math.tan(relativePitchRad) / Math.tan(verticalFov * 0.5);
        if (ndcX < -3.0 || ndcX > 3.0 || ndcY < -3.0 || ndcY > 3.0) return null;
        float screenX = (float) ((ndcX * 0.5 + 0.5) * screenW);
        float screenY = (float) ((0.5 - ndcY * 0.5) * screenH);
        return new float[]{screenX, screenY};
    }

    private void applyPreLayoutDirectionSearch(List<MrCardSnapshot> snapshots, float deltaTime, int screenW, int screenH) {
        List<MrCardSnapshot> ordered = new ArrayList<>(snapshots);
        ordered.sort(Comparator
                .comparing((MrCardSnapshot s) -> !s.isFocused)
                .thenComparing((MrCardSnapshot s) -> !s.isHostile)
                .thenComparingDouble(s -> s.distance));

        List<MrCardSnapshot> placed = new ArrayList<>();
        for (MrCardSnapshot snap : ordered) {
            if (!snap.isFocused) {
                chooseBestCandidatePosition(snap, deltaTime, placed, screenW, screenH);
            } else {
                TrackedCard tracked = activeCards.get(snap.entityUuid);
                if (tracked != null) {
                    tracked.layoutOffsetX = smoothApproach(tracked.layoutOffsetX, 0.0f, deltaTime);
                    tracked.layoutOffsetY = smoothApproach(tracked.layoutOffsetY, 0.0f, deltaTime);
                }
            }
            syncConnectorAfterCardMove(snap);
            placed.add(snap);
        }
    }

    private void chooseBestCandidatePosition(MrCardSnapshot snap, float deltaTime, List<MrCardSnapshot> placed, int screenW, int screenH) {
        if (placed.isEmpty()) return;
        TrackedCard tracked = activeCards.get(snap.entityUuid);
        float originalX = snap.cardX;
        float originalY = snap.cardY;
        float ratio = snap.connectorEdgeRatio;
        float originalConnectorX = originalX + snap.cardWidth * ratio;
        float originalConnectorY = snap.connectorOnTopEdge ? originalY : originalY + snap.cardHeight;
        float originalAcX = originalConnectorX - snap.anchorX;
        float originalAcY = originalConnectorY - snap.anchorY;
        float originalAcLength = (float) Math.sqrt(originalAcX * originalAcX + originalAcY * originalAcY);
        float originalAcDirectionX = originalAcLength > 0.001f ? originalAcX / originalAcLength : snap.connectorDirectionX;
        float originalAcDirectionY = originalAcLength > 0.001f ? originalAcY / originalAcLength : snap.connectorDirectionY;
        float segmentLength = MrConstants.RIGID_SEGMENT_LENGTH * snap.scale;
        float cPointRadius = Math.max(4.0f, Math.min(snap.cardWidth, snap.cardHeight) * 0.08f);
        float[][] directions = buildCandidateDirections(snap.anchorX, snap.anchorY, screenW, screenH);
        if (tracked != null && tracked.hasLastLayoutDirection) {
            directions = prioritizeDirection(directions, tracked.lastLayoutDirectionX, tracked.lastLayoutDirectionY);
        }

        float bestX = originalX;
        float bestY = originalY;
        float bestDirectionX = snap.connectorDirectionX;
        float bestDirectionY = snap.connectorDirectionY;
        boolean bestConnectorOnTop = snap.connectorOnTopEdge;
        float bestScore = Float.MAX_VALUE;

        for (float[] direction : directions) {
            float connectorX = snap.jointX + direction[0] * segmentLength;
            float connectorY = snap.jointY + direction[1] * segmentLength;
            boolean connectorOnTop = direction[1] >= 0.0f;
            float cardX = connectorX - snap.cardWidth * ratio;
            float cardY = connectorOnTop ? connectorY : connectorY - snap.cardHeight;
            float score = computeLayoutCandidateScore(cardX, cardY, snap.cardWidth, snap.cardHeight, connectorX, connectorY, cPointRadius, placed, screenW, screenH, originalX, originalY);
            if (score < bestScore) {
                bestScore = score;
                bestX = cardX;
                bestY = cardY;
                bestDirectionX = direction[0];
                bestDirectionY = direction[1];
                bestConnectorOnTop = connectorOnTop;
            }
            if (score <= 0.0f) break;
        }

        if (bestScore > snap.cardWidth * snap.cardHeight * 0.2f) {
            float step = Math.max(8.0f, segmentLength * 0.5f);
            for (int i = 1; i <= 8; i++) {
                float length = originalAcLength + step * i;
                float connectorX = snap.anchorX + originalAcDirectionX * length;
                float connectorY = snap.anchorY + originalAcDirectionY * length;
                boolean connectorOnTop = originalAcDirectionY >= 0.0f;
                float cardX = connectorX - snap.cardWidth * ratio;
                float cardY = connectorOnTop ? connectorY : connectorY - snap.cardHeight;
                float score = computeLayoutCandidateScore(cardX, cardY, snap.cardWidth, snap.cardHeight, connectorX, connectorY, cPointRadius, placed, screenW, screenH, originalX, originalY);
                if (score < bestScore) {
                    bestScore = score;
                    bestX = cardX;
                    bestY = cardY;
                    bestDirectionX = originalAcDirectionX;
                    bestDirectionY = originalAcDirectionY;
                    bestConnectorOnTop = connectorOnTop;
                }
                if (score <= 0.0f) break;
            }
        }

        if (tracked == null) {
            snap.cardX = bestX;
            snap.cardY = bestY;
            snap.connectorDirectionX = bestDirectionX;
            snap.connectorDirectionY = bestDirectionY;
            snap.connectorOnTopEdge = bestConnectorOnTop;
            return;
        }
        float targetOffsetX = bestX - originalX;
        float targetOffsetY = bestY - originalY;
        tracked.layoutOffsetX = smoothApproach(tracked.layoutOffsetX, targetOffsetX, deltaTime);
        tracked.layoutOffsetY = smoothApproach(tracked.layoutOffsetY, targetOffsetY, deltaTime);
        snap.cardX = originalX + tracked.layoutOffsetX;
        snap.cardY = originalY + tracked.layoutOffsetY;
        snap.connectorDirectionX = bestDirectionX;
        snap.connectorDirectionY = bestDirectionY;
        snap.connectorOnTopEdge = bestConnectorOnTop;
        float magnitude = (float) Math.sqrt(targetOffsetX * targetOffsetX + targetOffsetY * targetOffsetY);
        if (magnitude > 0.001f) {
            tracked.lastLayoutDirectionX = targetOffsetX / magnitude;
            tracked.lastLayoutDirectionY = targetOffsetY / magnitude;
            tracked.hasLastLayoutDirection = true;
        }
    }


    private float[][] prioritizeDirection(float[][] directions, float dirX, float dirY) {
        float[][] ordered = new float[directions.length][2];
        int index = 0;
        float bestScore = -Float.MAX_VALUE;
        int bestIndex = 0;
        for (int i = 0; i < directions.length; i++) {
            float score = directions[i][0] * dirX + directions[i][1] * dirY;
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        for (int i = bestIndex; i < directions.length; i++) {
            ordered[index++] = directions[i];
        }
        for (int i = 0; i < bestIndex; i++) {
            ordered[index++] = directions[i];
        }
        return ordered;
    }

    private float[][] buildCandidateDirections(float anchorX, float anchorY, int screenW, int screenH) {
        float horizontal = anchorX < screenW * 0.5f ? 1.0f : -1.0f;
        float normalizedY = anchorY / (float) screenH;
        float vertical = normalizedY < 0.5f ? 1.0f : -1.0f;
        float segmentLength = 1.0f;
        float abX;
        float abY;
        if (normalizedY < 0.2f) {
            abX = 0.0f;
            abY = segmentLength;
        } else if (normalizedY > 0.8f) {
            abX = 0.0f;
            abY = -segmentLength;
        } else {
            abX = horizontal * segmentLength;
            abY = 0.0f;
        }

        double bcRad = Math.toRadians(vertical > 0.0f
                ? MrConstants.BC_REST_ANGLE_DEGREES
                : -MrConstants.BC_REST_ANGLE_DEGREES);
        float bcX = horizontal * (float) Math.cos(bcRad) * segmentLength;
        float bcY = (float) Math.sin(bcRad) * segmentLength;
        float baseAngle = normalizeAngle((float) Math.atan2(abY + bcY, abX + bcX));
        float theta = Math.abs(baseAngle);
        if (theta > Math.PI * 0.5f) {
            theta = (float) Math.PI - theta;
        }

        float[] candidateAbsAngles = baseAngle >= Math.PI * 0.5f || baseAngle <= -Math.PI * 0.5f
                ? new float[]{copySign((float) Math.PI - theta, baseAngle), copySign((float) Math.PI - theta, -baseAngle), copySign((float) Math.PI * 0.5f + theta, baseAngle), copySign((float) Math.PI * 0.5f + theta, -baseAngle), copySign((float) Math.PI * 0.5f - theta, baseAngle), copySign((float) Math.PI * 0.5f - theta, -baseAngle), copySign(theta, baseAngle), copySign(theta, -baseAngle)}
                : new float[]{copySign(theta, baseAngle), copySign(theta, -baseAngle), copySign((float) Math.PI * 0.5f - theta, baseAngle), copySign((float) Math.PI * 0.5f - theta, -baseAngle), copySign((float) Math.PI * 0.5f + theta, baseAngle), copySign((float) Math.PI * 0.5f + theta, -baseAngle), copySign((float) Math.PI - theta, baseAngle), copySign((float) Math.PI - theta, -baseAngle)};

        float[][] directions = new float[candidateAbsAngles.length][2];
        for (int i = 0; i < candidateAbsAngles.length; i++) {
            directions[i][0] = (float) Math.cos(candidateAbsAngles[i]);
            directions[i][1] = (float) Math.sin(candidateAbsAngles[i]);
        }
        return directions;
    }

    private float normalizeAngle(float angle) {
        while (angle > Math.PI) angle -= (float) Math.PI * 2.0f;
        while (angle <= -Math.PI) angle += (float) Math.PI * 2.0f;
        return angle;
    }

    private float copySign(float value, float sign) {
        return sign >= 0.0f ? Math.abs(value) : -Math.abs(value);
    }

    private float computeLayoutCandidateScore(float x, float y, float width, float height, float connectorX, float connectorY, float cPointRadius, List<MrCardSnapshot> placed, int screenW, int screenH, float originalX, float originalY) {
        float score = 0.0f;
        for (MrCardSnapshot other : placed) {
            score += computeCPointOccupancyPenalty(connectorX, connectorY, cPointRadius, other);
            score += computeOverlapArea(x, y, width, height, other.cardX, other.cardY, other.cardWidth, other.cardHeight);
        }
        score += computeOutOfScreenPenalty(x, y, width, height, screenW, screenH);
        float dx = x - originalX;
        float dy = y - originalY;
        score += (float) Math.sqrt(dx * dx + dy * dy) * 0.05f;
        return score;
    }

    private float computeCPointOccupancyPenalty(float connectorX, float connectorY, float radius, MrCardSnapshot other) {
        float closestX = Math.max(other.cardX, Math.min(connectorX, other.cardX + other.cardWidth));
        float closestY = Math.max(other.cardY, Math.min(connectorY, other.cardY + other.cardHeight));
        float dx = connectorX - closestX;
        float dy = connectorY - closestY;
        float distanceSquared = dx * dx + dy * dy;
        if (distanceSquared > radius * radius) return 0.0f;
        return other.cardWidth * other.cardHeight;
    }

    private float computeOverlapArea(float ax, float ay, float aw, float ah, float bx, float by, float bw, float bh) {
        float overlapW = Math.min(ax + aw, bx + bw) - Math.max(ax, bx);
        float overlapH = Math.min(ay + ah, by + bh) - Math.max(ay, by);
        if (overlapW <= 0.0f || overlapH <= 0.0f) return 0.0f;
        return overlapW * overlapH;
    }

    private float computeOutOfScreenPenalty(float x, float y, float width, float height, int screenW, int screenH) {
        float left = Math.max(0.0f, -x);
        float top = Math.max(0.0f, -y);
        float right = Math.max(0.0f, x + width - screenW);
        float bottom = Math.max(0.0f, y + height - screenH);
        return (left + top + right + bottom) * 20.0f;
    }

    private boolean hasSnapshot(List<MrCardSnapshot> snapshots, String uuid) {
        if (uuid == null) return false;
        for (MrCardSnapshot snapshot : snapshots) {
            if (uuid.equals(snapshot.entityUuid)) return true;
        }
        return false;
    }

    private boolean isFocusedDetailOutputFinished(List<MrCardSnapshot> snapshots, String focusedUuid) {
        for (MrCardSnapshot snapshot : snapshots) {
            if (focusedUuid.equals(snapshot.entityUuid)) return snapshot.focusedDetailOutputFinished;
        }
        return false;
    }

    private void updateFocusedDetailText(TrackedCard tracked, NearbyEntityData entity, float deltaTime) {
        String detailText = buildFocusedDetailText(entity);
        if (!detailText.equals(tracked.focusedDetailText)) {
            tracked.focusedDetailText = detailText;
            tracked.focusedDetailVisibleChars = 0.0f;
            tracked.focusedDetailOutputFinished = detailText.isEmpty();
        }
        if (!tracked.focusedDetailOutputFinished) {
            tracked.focusedDetailVisibleChars += deltaTime * MrConstants.FOCUS_TEXT_CHARS_PER_SECOND;
            if (tracked.focusedDetailVisibleChars >= tracked.focusedDetailText.length()) {
                tracked.focusedDetailVisibleChars = tracked.focusedDetailText.length();
                tracked.focusedDetailOutputFinished = true;
            }
        }
    }

    private void resetFocusedDetailText(TrackedCard tracked) {
        tracked.focusedDetailText = "";
        tracked.focusedDetailVisibleChars = 0.0f;
        tracked.focusedDetailOutputFinished = false;
    }

    private String buildFocusedDetailText(NearbyEntityData entity) {
        StringBuilder builder = new StringBuilder();
        builder.append("ID ").append(entity.getEntityId());
        builder.append("\nHP ").append(String.format("%.1f/%.1f", entity.getHealth(), entity.getMaxHealth()));
        builder.append("  DIST ").append(String.format("%.1fm", entity.getDistance()));
        builder.append("\nATK ").append(String.format("%.1f", entity.getAttackDamage()));
        builder.append("  ARM ").append(String.format("%.1f", entity.getArmorValue()));
        builder.append("\nLOS ").append(entity.isLineOfSight() ? "LOCKED" : "LOST");
        if (entity.getMainHandItemId() != null && !entity.getMainHandItemId().isEmpty()) {
            builder.append("  MAIN ").append(entity.getMainHandItemId());
        }
        return builder.toString();
    }

    private void transitionFocusBackToScanning() {
        stateMachine.transitionToScanning();
        scanningTimer = MrConstants.SCANNING_WARMUP;
        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
        aimWarmupTimer = 0.0f;
        gazeTimer = 0.0f;
        lastGazeUuid = null;
    }

    private void transitionFocusBackToScanningForGazeSwitch(String nextUuid) {
        stateMachine.transitionToScanning();
        scanningTimer = MrConstants.SCANNING_WARMUP;
        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
        aimWarmupTimer = 0.0f;
        gazeTimer = 0.0f;
        lastGazeUuid = nextUuid;
    }

    private void updateGazeTimers(String crosshairUuid, float deltaTime) {
        if (crosshairUuid.equals(lastGazeUuid)) {
            aimWarmupTimer += deltaTime;
            if (aimWarmupTimer >= MrConstants.FOCUS_AIM_WARMUP_SECONDS) {
                gazeTimer += deltaTime;
            }
        } else {
            lastGazeUuid = crosshairUuid;
            aimWarmupTimer = 0.0f;
            gazeTimer = 0.0f;
        }
    }

    private float computeBaseCardWidth(int screenW) {
        float width = screenW * MrConstants.CARD_BASE_WIDTH_RATIO;
        return Math.max(MrConstants.CARD_MIN_BASE_WIDTH, Math.min(MrConstants.CARD_MAX_BASE_WIDTH, width));
    }

    private float computeBaseCardHeight(int screenH) {
        float height = screenH * MrConstants.CARD_BASE_HEIGHT_RATIO;
        return Math.max(MrConstants.CARD_MIN_BASE_HEIGHT, Math.min(MrConstants.CARD_MAX_BASE_HEIGHT, height));
    }

    private float clampFocusedScale(float scale, float baseCardW, float baseCardH, int screenW, int screenH) {
        float maxByWidth = screenW * MrConstants.CARD_MAX_FOCUSED_WIDTH_RATIO / Math.max(baseCardW, 1.0f);
        float maxArea = screenW * screenH * MrConstants.CARD_MAX_FOCUSED_AREA_RATIO;
        float maxByArea = (float) Math.sqrt(maxArea / Math.max(baseCardW * baseCardH, 1.0f));
        return Math.min(scale, Math.min(maxByWidth, maxByArea));
    }

    private float computeCardHeight(float baseCardH, float scale, float cardW, boolean isFocused, String focusedDetailText) {
        float baseHeight = baseCardH * scale;
        if (!isFocused || focusedDetailText == null || focusedDetailText.isEmpty()) return baseHeight;
        int lines = estimateWrappedLineCount(focusedDetailText, cardW);
        float detailTop = MrConstants.CONTENT_PADDING_Y + MrConstants.FONT_LINE_HEIGHT + 2.0f + MrConstants.CONTENT_BAR_SPACING + MrConstants.FONT_LINE_HEIGHT + 8.0f;
        float requiredHeight = detailTop + lines * MrConstants.FONT_LINE_HEIGHT + MrConstants.CONTENT_PADDING_Y;
        return Math.max(baseHeight, requiredHeight);
    }

    private int estimateWrappedLineCount(String text, float cardW) {
        float availableWidth = Math.max(1.0f, cardW - MrConstants.CONTENT_PADDING_X * 2.0f);
        float averageCharWidth = 6.0f;
        int maxCharsPerLine = Math.max(1, (int) (availableWidth / averageCharWidth));
        int lines = 0;
        String[] explicitLines = text.split("\n", -1);
        for (String line : explicitLines) {
            lines += Math.max(1, (line.length() + maxCharsPerLine - 1) / maxCharsPerLine);
        }
        return Math.max(1, lines);
    }

    private TargetGeometry computeTargetGeometry(float anchorX, float anchorY, float scale, float cardW, float cardH, int screenW, int screenH) {
        float[] joint = computeRestJointPoint(anchorX, anchorY, scale, screenW, screenH);
        float normalizedX = anchorX / (float) screenW;
        float normalizedY = anchorY / (float) screenH;
        boolean connectorOnTop = normalizedY < 0.5f;
        float baseAngle = buildTargetAngle(normalizedX, connectorOnTop);
        float segmentLength = MrConstants.RIGID_SEGMENT_LENGTH * scale;
        float directionX = (float) Math.cos(baseAngle);
        float directionY = (float) Math.sin(baseAngle);
        float connectorX = joint[0] + directionX * segmentLength;
        float connectorY = joint[1] + directionY * segmentLength;
        float ratio = computeConnectorEdgeRatio(anchorX, screenW);
        float cardX = connectorX - cardW * ratio;
        float cardY = connectorOnTop ? connectorY : connectorY - cardH;
        return new TargetGeometry(cardX, cardY, directionX, directionY, connectorOnTop, ratio);
    }

    private float buildTargetAngle(float normalizedX, boolean connectorOnTop) {
        float horizontalDirection = normalizedX < 0.5f ? 1.0f : -1.0f;
        float verticalDirection = connectorOnTop ? 1.0f : -1.0f;
        float angle = (float) Math.toRadians(MrConstants.BC_REST_ANGLE_DEGREES);
        return (float) Math.atan2(verticalDirection * Math.sin(angle), horizontalDirection * Math.cos(angle));
    }

    private float computeConnectorEdgeRatio(float anchorX, int screenW) {
        float normalizedX = anchorX / (float) screenW;
        float ratio = MrConstants.CONNECTOR_EDGE_MIN_RATIO
                + (MrConstants.CONNECTOR_EDGE_MAX_RATIO - MrConstants.CONNECTOR_EDGE_MIN_RATIO) * normalizedX;
        return Math.max(MrConstants.CONNECTOR_EDGE_MIN_RATIO, Math.min(MrConstants.CONNECTOR_EDGE_MAX_RATIO, ratio));
    }

    private float smoothApproach(float current, float target, float deltaTime) {
        float t = 1.0f - (float) Math.exp(-MrConstants.UI_TRANSITION_SPEED * deltaTime);
        return current + (target - current) * Math.max(0.0f, Math.min(1.0f, t));
    }

    private float[] computeRestJointPoint(float ax, float ay, float scale, int sw, int sh) {
        float normalizedY = ay / (float) sh;
        float segmentLength = MrConstants.RIGID_SEGMENT_LENGTH * scale;

        if (normalizedY < 0.2f) {
            return new float[]{ax, ay + segmentLength};
        }
        if (normalizedY > 0.8f) {
            return new float[]{ax, ay - segmentLength};
        }

        float direction = ax < sw * 0.5f ? 1.0f : -1.0f;
        return new float[]{ax + direction * segmentLength, ay};
    }

    private void updateConnectorPoint(MrCardSnapshot snap, float anchorX, int screenW) {
        snap.connectorEdgeRatio = computeConnectorEdgeRatio(anchorX, screenW);
        syncConnectorAfterCardMove(snap);
    }

    private void syncConnectorAfterCardMove(MrCardSnapshot snap) {
        snap.connectorX = snap.cardX + snap.cardWidth * snap.connectorEdgeRatio;
        snap.connectorY = snap.connectorOnTopEdge ? snap.cardY : snap.cardY + snap.cardHeight;
    }

    private void precomputeVisuals(MrCardSnapshot snap) {
        float healthRatio = snap.maxHealth > 0.0f
                ? Math.max(0.0f, Math.min(1.0f, snap.health / snap.maxHealth))
                : 0.0f;

        int baseColor = snap.isHostile ? MrConstants.COLOR_HOSTILE : MrConstants.COLOR_NEUTRAL;
        int accentR = (baseColor >> 16) & 0xFF;
        int accentG = (baseColor >> 8) & 0xFF;
        int accentB = baseColor & 0xFF;

        int textAlphaInt = (int) (Math.max(0.0f, Math.min(1.0f, snap.alpha)) * 255.0f) & 0xFF;
        float barFullWidth = Math.max(0.0f, snap.cardWidth - MrConstants.CONTENT_BAR_MARGIN);
        int healthColor;
        if (healthRatio > 0.6f) {
            healthColor = (textAlphaInt << 24) | 0x33DD66;
        } else if (healthRatio > 0.3f) {
            healthColor = (textAlphaInt << 24) | 0xFFCC33;
        } else {
            healthColor = (textAlphaInt << 24) | 0xFF3333;
        }

        snap.accentColor = baseColor;
        snap.accentR = accentR;
        snap.accentG = accentG;
        snap.accentB = accentB;
        snap.textAlphaColor = (textAlphaInt << 24) | 0xFFFFFF;
        snap.accentTextColor = (textAlphaInt << 24) | (accentR << 16) | (accentG << 8) | accentB;
        snap.healthBarBgColor = (textAlphaInt << 24) | 0x333333;
        snap.healthBarColor = healthColor;
        snap.healthBarFullWidth = barFullWidth;
        snap.healthBarFillWidth = barFullWidth * healthRatio;
        snap.glitchOffset = 0;
        snap.distanceText = String.format("%.1fm", snap.distance);
        snap.attackText = snap.attackDamage > 0 ? String.format("%.0f", snap.attackDamage) : null;
        snap.armorText = snap.armorValue > 0 ? String.format("%.0f", snap.armorValue) : null;
        snap.distanceIconItemId = "minecraft:compass";
        snap.attackIconItemId = "minecraft:iron_sword";
        snap.armorIconItemId = "minecraft:iron_chestplate";
        snap.contentStartX = snap.cardWidth > 0.0f ? MrConstants.CONTENT_PADDING_X : 0.0f;
        snap.contentStartY = snap.cardHeight > 0.0f ? MrConstants.CONTENT_PADDING_Y : 0.0f;
        snap.nameIconX = snap.contentStartX;
        snap.nameIconY = snap.contentStartY - 2.0f;
        snap.nameTextX = snap.nameIconX + MrConstants.STATS_ICON_SIZE + MrConstants.STATS_ICON_TEXT_GAP;
        snap.nameTextY = snap.contentStartY;
        snap.contentNameEndY = snap.contentStartY + MrConstants.FONT_LINE_HEIGHT + 2.0f;
        snap.contentBarEndY = snap.contentNameEndY + MrConstants.CONTENT_BAR_SPACING;
        snap.contentStatsY = snap.contentBarEndY;

        float cursorX = snap.contentStartX;
        float iconY = snap.contentStatsY - 4.0f;
        snap.distanceIconX = cursorX;
        snap.distanceIconY = iconY;
        snap.distanceTextX = snap.distanceIconX + MrConstants.STATS_ICON_SIZE + MrConstants.STATS_ICON_TEXT_GAP;
        cursorX = snap.distanceTextX + snap.distanceText.length() * 6.0f + MrConstants.STATS_GROUP_GAP;

        snap.attackIconX = cursorX;
        snap.attackIconY = iconY;
        snap.atkTextX = snap.attackIconX + MrConstants.STATS_ICON_SIZE + MrConstants.STATS_ICON_TEXT_GAP;
        String attackText = snap.attackText != null ? snap.attackText : "0";
        cursorX = snap.atkTextX + attackText.length() * 6.0f + MrConstants.STATS_GROUP_GAP;

        snap.armorIconX = cursorX;
        snap.armorIconY = iconY;
        snap.defTextX = snap.armorIconX + MrConstants.STATS_ICON_SIZE + MrConstants.STATS_ICON_TEXT_GAP;
    }
}
