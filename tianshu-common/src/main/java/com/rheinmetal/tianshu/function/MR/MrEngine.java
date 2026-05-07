package com.rheinmetal.tianshu.function.MR;

import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.provider.IPlayerStateProvider;
import com.rheinmetal.tianshu.provider.IRenderContextProvider;
import com.rheinmetal.tianshu.snapshot.MatrixSnapshot;
import com.rheinmetal.tianshu.snapshot.MrManualFocusTargetData;
import com.rheinmetal.tianshu.snapshot.NearbyEntityData;
import com.rheinmetal.tianshu.snapshot.PositionData;
import com.rheinmetal.tianshu.snapshot.WorldEnvironmentData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;


public class MrEngine {

    private static final int OCCLUSION_MISS_GRACE_TICKS = 6;

    private final MrStateMachine stateMachine = new MrStateMachine();
    private final ConcurrentLinkedQueue<MrCardSnapshot> outputQueue = new ConcurrentLinkedQueue<>();

    private final IEnvironmentAwarenessProvider environmentProvider;
    private final IRenderContextProvider renderContextProvider;
    private final IPlayerStateProvider playerStateProvider;
    private final MrTuningProvider tuningProvider;

    private final Map<String, TrackedCard> activeCards = new LinkedHashMap<>();
    private final List<NearbyEntityData> tickEntities = new ArrayList<>();
    private final List<MrCardSnapshot> frameSnapshots = new ArrayList<>();
    private final Set<String> currentUuids = new HashSet<>();
    private final List<MrCardSnapshot> orderedSnapshots = new ArrayList<>();
    private final List<MrCardSnapshot> placedSnapshots = new ArrayList<>();
    private final List<EntityScreenRect> entityScreenRects = new ArrayList<>();
    private boolean initialStaggerDone = false;
    private int initialStaggerIndex = 0;

    private float scanningTimer = 0.0f;
    private float aimWarmupTimer = 0.0f;
    private float gazeTimer = 0.0f;
    private float focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
    private int debugTickCounter = 0;
    private String lastGazeUuid = null;
    private MrManualFocusTargetData manualFocusPreviewTarget = null;
    private float manualFocusPreviewProgress = 0.0f;
    private boolean scanningCardsEnabled = true;
    private boolean focusInteractionEnabled = true;
    private MrManualFocusTargetData manualFocusTarget = null;

    private volatile boolean running = false;
    private volatile boolean closing = false;

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
        final int connectorEdge;
        final boolean connectorOnTopEdge;
        final float connectorEdgeRatio;

        TargetGeometry(float cardX, float cardY, float connectorDirectionX, float connectorDirectionY, int connectorEdge, boolean connectorOnTopEdge, float connectorEdgeRatio) {
            this.cardX = cardX;
            this.cardY = cardY;
            this.connectorDirectionX = connectorDirectionX;
            this.connectorDirectionY = connectorDirectionY;
            this.connectorEdge = connectorEdge;
            this.connectorOnTopEdge = connectorOnTopEdge;
            this.connectorEdgeRatio = connectorEdgeRatio;
        }
    }

    private static final class LayoutCandidate {
        final float jointX;
        final float jointY;
        final float cardX;
        final float cardY;
        final float connectorDirectionX;
        final float connectorDirectionY;
        final int connectorEdge;
        final boolean connectorOnTopEdge;
        final float score;
        final boolean acceptable;

        LayoutCandidate(float jointX, float jointY, float cardX, float cardY, float connectorDirectionX, float connectorDirectionY, int connectorEdge, boolean connectorOnTopEdge, float score, boolean acceptable) {
            this.jointX = jointX;
            this.jointY = jointY;
            this.cardX = cardX;
            this.cardY = cardY;
            this.connectorDirectionX = connectorDirectionX;
            this.connectorDirectionY = connectorDirectionY;
            this.connectorEdge = connectorEdge;
            this.connectorOnTopEdge = connectorOnTopEdge;
            this.score = score;
            this.acceptable = acceptable;
        }
    }

    private static final class CandidateEvaluation {
        final float score;
        final boolean acceptable;

        CandidateEvaluation(float score, boolean acceptable) {
            this.score = score;
            this.acceptable = acceptable;
        }
    }

    private static final class EntityScreenRect {
        final String uuid;
        final float left;
        final float top;
        final float right;
        final float bottom;
        final float priorityWeight;

        EntityScreenRect(String uuid, float left, float top, float right, float bottom, float priorityWeight) {
            this.uuid = uuid;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.priorityWeight = priorityWeight;
        }
    }

    private static final class LayoutContext {
        final List<MrCardSnapshot> placed;
        final List<EntityScreenRect> entityRects;
        final int screenW;
        final int screenH;
        final float originalX;
        final float originalY;
        final float originalJointX;
        final float originalJointY;
        final float originalDirectionX;
        final float originalDirectionY;
        final String entityUuid;

        LayoutContext(List<MrCardSnapshot> placed, List<EntityScreenRect> entityRects, int screenW, int screenH, float originalX, float originalY, float originalJointX, float originalJointY, float originalDirectionX, float originalDirectionY, String entityUuid) {
            this.placed = placed;
            this.entityRects = entityRects;
            this.screenW = screenW;
            this.screenH = screenH;
            this.originalX = originalX;
            this.originalY = originalY;
            this.originalJointX = originalJointX;
            this.originalJointY = originalJointY;
            this.originalDirectionX = originalDirectionX;
            this.originalDirectionY = originalDirectionY;
            this.entityUuid = entityUuid;
        }
    }

    private static final class AbDirection {
        final float x;
        final float y;

        AbDirection(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class BcDirection {
        final float x;
        final float y;

        BcDirection(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class Segment {
        final float x1;
        final float y1;
        final float x2;
        final float y2;

        Segment(float x1, float y1, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }

    private static final class SegmentPair {
        final Segment ab;
        final Segment bc;

        SegmentPair(Segment ab, Segment bc) {
            this.ab = ab;
            this.bc = bc;
        }
    }

    private static final class LayoutEvaluationOptions {
        final boolean checkLines;

        LayoutEvaluationOptions(boolean checkLines) {
            this.checkLines = checkLines;
        }
    }

    private static final LayoutEvaluationOptions FULL_LAYOUT_EVALUATION = new LayoutEvaluationOptions(true);
    private static final LayoutEvaluationOptions CARD_ONLY_LAYOUT_EVALUATION = new LayoutEvaluationOptions(false);

    private static final float LAYOUT_ACCEPTABLE_SCORE = 0.001f;

    private static final AbDirection[] AB_DIRECTIONS = new AbDirection[]{
            new AbDirection(1.0f, 0.0f),
            new AbDirection(-1.0f, 0.0f),
            new AbDirection(0.0f, -1.0f),
            new AbDirection(0.0f, 1.0f)
    };

    private static final float[] AC_EXTENSION_FACTORS = new float[]{1.25f, 1.5f, 1.75f, 2.0f};

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
        boolean wasOcclusionVisible = true;
        String focusedDetailText = "";
        String fixedDetailText = null;
        float focusedDetailVisibleChars = 0.0f;
        boolean focusedDetailOutputFinished = false;
        float layoutOffsetX = 0.0f;
        float layoutOffsetY = 0.0f;
        float lockedQuadrantX = 0.0f;
        float lockedQuadrantY = 0.0f;
        boolean hasLockedQuadrant = false;
        boolean suppressingForLimit = false;
        float lastLayoutDirectionX = 0.0f;
        float lastLayoutDirectionY = 0.0f;
        boolean hasLastLayoutDirection = false;
        int projectionMissTicks = 0;
        int occlusionMissTicks = 0;
        boolean animationTickedThisFrame = false;

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
        this(environmentProvider, renderContextProvider, null, MrTuningProvider.defaults());
    }

    public MrEngine(
            IEnvironmentAwarenessProvider environmentProvider,
            IRenderContextProvider renderContextProvider,
            MrTuningProvider tuningProvider
    ) {
        this(environmentProvider, renderContextProvider, null, tuningProvider);
    }

    public MrEngine(
            IEnvironmentAwarenessProvider environmentProvider,
            IRenderContextProvider renderContextProvider,
            IPlayerStateProvider playerStateProvider,
            MrTuningProvider tuningProvider
    ) {
        this.environmentProvider = environmentProvider;
        this.renderContextProvider = renderContextProvider;
        this.playerStateProvider = playerStateProvider;
        this.tuningProvider = tuningProvider != null ? tuningProvider : MrTuningProvider.defaults();
    }

    public MrStateMachine getStateMachine() {
        return stateMachine;
    }

    public ConcurrentLinkedQueue<MrCardSnapshot> getOutputQueue() {
        return outputQueue;
    }

    public void tickAnimations(float deltaTime) {
        if (deltaTime <= 0.0f || deltaTime > 0.25f) return;
        for (TrackedCard tracked : activeCards.values()) {
            if (tracked.animationTickedThisFrame) continue;
            tracked.animation.tick(deltaTime);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isClosing() {
        return closing;
    }

    public boolean isCloseAnimationFinished() {
        return closing && activeCards.isEmpty() && outputQueue.isEmpty();
    }

    public double getRequiredRadius() {
        return (stateMachine.isActive() || closing) ? MrConstants.MR_RANGE : 0.0;
    }

    public void start() {
        stateMachine.transitionToScanning();
        running = true;
        scanningTimer = 0.0f;
        aimWarmupTimer = 0.0f;
        gazeTimer = 0.0f;
        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
        lastGazeUuid = null;
        manualFocusPreviewTarget = null;
        manualFocusPreviewProgress = 0.0f;
        focusInteractionEnabled = true;
        scanningCardsEnabled = true;
        manualFocusTarget = null;
        initialStaggerDone = false;
        initialStaggerIndex = 0;
        debugTickCounter = 0;
        debugLog("start state=" + stateMachine.getState() + " running=" + running + " range=" + MrConstants.MR_RANGE);
    }

    public void setScanningCardsEnabled(boolean enabled) {
        scanningCardsEnabled = enabled;
        if (enabled && running && !closing && !stateMachine.isFocusing()) {
            stateMachine.transitionToScanning();
        }
    }

    public boolean isScanningCardsEnabled() {
        return scanningCardsEnabled;
    }

    public void setFocusInteractionEnabled(boolean enabled) {
        if (focusInteractionEnabled == enabled) return;
        focusInteractionEnabled = enabled;
        clearManualFocusPreview();
        resetGazeTimers();
        if (!enabled && stateMachine.isFocusing()) {
            manualFocusTarget = null;
            focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
            if (running && !closing) {
                stateMachine.transitionToScanning();
            }
        }
    }

    public boolean isFocusInteractionEnabled() {
        return focusInteractionEnabled;
    }

    public void startManualFocus(MrManualFocusTargetData target, boolean includeBackgroundCards) {
        if (!focusInteractionEnabled) return;
        if (target == null || target.getUuid() == null || target.getUuid().isEmpty()) return;
        clearManualFocusPreview();
        closing = false;
        running = true;
        scanningCardsEnabled = includeBackgroundCards;
        manualFocusTarget = target;
        stateMachine.forceFocusing(target.getUuid());
        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
        aimWarmupTimer = 0.0f;
        gazeTimer = 0.0f;
        lastGazeUuid = null;
        TrackedCard tracked = activeCards.get(target.getUuid());
        boolean created = false;
        if (tracked == null) {
            tracked = new TrackedCard(target.getUuid());
            activeCards.put(target.getUuid(), tracked);
            created = true;
        }
        updateFixedFocusedDetailText(tracked, target.getDetailText(), 0.0f);
        if (created || tracked.animation.isFullyDead()) {
            tracked.animation.restartAppear();
            tracked.layout.reset();
            tracked.visualScaleFactor = 1.0f;
            tracked.visualAlphaFactor = 1.0f;
            tracked.focusedDetailVisibleChars = 0.0f;
            tracked.focusedDetailOutputFinished = false;
        } else {
            tracked.animation.recoverAppear();
        }
        debugLog("manualFocus target=" + target.getUuid() + " background=" + includeBackgroundCards);
    }

    public void clearManualFocusTarget() {
        manualFocusTarget = null;
    }

    public void previewManualFocusProgress(MrManualFocusTargetData target, boolean includeBackgroundCards, float progress) {
        if (!focusInteractionEnabled) {
            clearManualFocusPreview();
            return;
        }
        if (target == null || target.getUuid() == null || target.getUuid().isEmpty()) {
            clearManualFocusPreview();
            return;
        }
        closing = false;
        running = true;
        scanningCardsEnabled = includeBackgroundCards;
        manualFocusPreviewTarget = target;
        manualFocusPreviewProgress = Math.max(0.0f, Math.min(1.0f, progress));
        manualFocusTarget = null;
        if (includeBackgroundCards && !stateMachine.isFocusing()) {
            stateMachine.transitionToScanning();
        }
    }

    public void clearManualFocusPreview() {
        manualFocusPreviewTarget = null;
        manualFocusPreviewProgress = 0.0f;
    }

    public void cancelManualFocus() {
        manualFocusTarget = null;
        beginClosing();
    }

    public boolean hasManualFocusTarget() {
        return manualFocusTarget != null;
    }

    public boolean hasManualFocusPreview() {
        return manualFocusPreviewTarget != null && manualFocusPreviewProgress > 0.0f;
    }

    public void stop() {
        closing = false;
        stateMachine.transitionToSilent();
        running = false;
        activeCards.clear();
        outputQueue.clear();
        scanningTimer = 0.0f;
        aimWarmupTimer = 0.0f;
        gazeTimer = 0.0f;
        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
        lastGazeUuid = null;
        manualFocusPreviewTarget = null;
        manualFocusPreviewProgress = 0.0f;
        focusInteractionEnabled = true;
        scanningCardsEnabled = true;
        manualFocusTarget = null;
        initialStaggerDone = false;
        initialStaggerIndex = 0;
        debugTickCounter = 0;
        debugLog("stop state=" + stateMachine.getState() + " running=" + running);
    }

    public void beginClosing() {
        if (!running || closing) return;
        closing = true;
        scanningTimer = 0.0f;
        aimWarmupTimer = 0.0f;
        gazeTimer = 0.0f;
        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
        lastGazeUuid = null;
        manualFocusPreviewTarget = null;
        manualFocusPreviewProgress = 0.0f;
        for (TrackedCard tracked : activeCards.values()) {
            tracked.animation.triggerDisappear();
        }
        stateMachine.transitionToSilent();
        debugLog("beginClosing activeCards=" + activeCards.size());
    }

    public void tick(PositionData playerPos) {
        tick(playerPos, MrConstants.TICK_DURATION);
    }

    public void tick(PositionData playerPos, float deltaTime) {
        if (playerPos == null || (!stateMachine.isActive() && !closing)) {
            if (debugTickCounter == 0) {
                debugLog("tick skipped active=" + stateMachine.isActive() + " closing=" + closing + " playerPos=" + (playerPos != null));
            }
            outputQueue.clear();
            return;
        }

        if (deltaTime <= 0.0f) {
            deltaTime = MrConstants.TICK_DURATION;
        }

        tickEntities.clear();
        if (!closing && scanningCardsEnabled) {
            tickEntities.addAll(environmentProvider.getNearbyEntities(MrConstants.MR_RANGE));
        }
        List<NearbyEntityData> entities = tickEntities;
        float environmentAlphaFactor = computeEnvironmentAlphaFactor();
        debugTickCounter++;
        boolean shouldLogDebug = debugTickCounter <= 10 || debugTickCounter % 20 == 0;
        if (entities.isEmpty() && shouldLogDebug) {
            debugLog("tick=" + debugTickCounter + " active=true entities=0 state=" + stateMachine.getState() + " player=" + playerPos.getX() + "," + playerPos.getY() + "," + playerPos.getZ());
        }

        entities.sort(Comparator
                .comparing(NearbyEntityData::isHostile).reversed()
                .thenComparingDouble(NearbyEntityData::getDistance));

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

        currentUuids.clear();
        frameSnapshots.clear();
        Set<String> selectedUuids = scanningCardsEnabled
                ? selectVisibleCardUuids(entities, playerPos, projData, mvData, matrixProjectionAvailable, screenW, screenH)
                : Collections.emptySet();
        buildVisibleEntityScreenRects(entities, playerPos, projData, mvData, matrixProjectionAvailable, selectedUuids, screenW, screenH, entityScreenRects);
        suppressUnselectedCards(selectedUuids);
        for (TrackedCard tracked : activeCards.values()) {
            tracked.animationTickedThisFrame = false;
        }
        int projectionFailedCount = 0;
        int projectionGraceCount = 0;
        int occlusionMissingCount = 0;
        int occlusionGraceCount = 0;
        int disappearingCount = 0;

        for (NearbyEntityData entity : entities) {
            String uuid = entity.getUuid();
            if (!selectedUuids.contains(uuid)) continue;

            TrackedCard tracked = activeCards.get(uuid);
            boolean entityInView = isEntityInsideCurrentFov(playerPos, entity, screenW, screenH);

            double entityWorldX = playerPos.getX() + entity.getRelativeX();
            double entityWorldY = playerPos.getY() + entity.getRelativeY() + entity.getBoundingHeight() + 0.2;
            double entityWorldZ = playerPos.getZ() + entity.getRelativeZ();

            float[] screenPos = null;
            if (matrixProjectionAvailable) {
                if (tracked != null && !entityInView) {
                    screenPos = MrProjector.projectUnclamped(
                            entityWorldX, entityWorldY, entityWorldZ,
                            mvData, projData,
                            screenW, screenH
                    );
                } else {
                    screenPos = MrProjector.project(
                            entityWorldX, entityWorldY, entityWorldZ,
                            mvData, projData,
                            screenW, screenH
                    );
                }
            }

            if (screenPos == null) {
                screenPos = projectFromPlayerView(playerPos, entity, screenW, screenH, tracked != null);
            }

            if (screenPos == null) {
                projectionFailedCount++;
                if (tracked != null) {
                    tracked.projectionMissTicks++;
                    tracked.animation.triggerDisappear();
                }
                continue;
            }

            float anchorX = screenPos[0];
            float anchorY = screenPos[1];
            boolean occlusionVisible = entity.isOcclusionVisible();
            boolean entityAlive = entity.getHealth() > 0.0f;

            if (tracked == null) {
                if (closing || !entityAlive || !occlusionVisible) continue;
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
            tracked.suppressingForLimit = false;
            tracked.projectionMissTicks = 0;

            if (entityAlive && occlusionVisible && !closing && entityInView) {
                tracked.occlusionMissTicks = 0;
                tracked.wasOcclusionVisible = true;
                tracked.lastSeenAnchorX = anchorX;
                tracked.lastSeenAnchorY = anchorY;
                tracked.hasLastSeenAnchor = true;
                tracked.animation.recoverAppear();
            } else if (entityAlive && !closing && entityInView) {
                occlusionMissingCount++;
                tracked.occlusionMissTicks++;
                if (tracked.occlusionMissTicks <= OCCLUSION_MISS_GRACE_TICKS) {
                    occlusionGraceCount++;
                    tracked.wasOcclusionVisible = true;
                    tracked.lastSeenAnchorX = anchorX;
                    tracked.lastSeenAnchorY = anchorY;
                    tracked.hasLastSeenAnchor = true;
                    tracked.animation.recoverAppear();
                } else {
                    tracked.wasOcclusionVisible = false;
                    if (!tracked.hasLastSeenAnchor) {
                        tracked.lastSeenAnchorX = anchorX;
                        tracked.lastSeenAnchorY = anchorY;
                        tracked.hasLastSeenAnchor = true;
                    }
                    anchorX = tracked.lastSeenAnchorX;
                    anchorY = tracked.lastSeenAnchorY;
                    tracked.animation.triggerDisappear();
                }
            } else {
                if (!tracked.hasLastSeenAnchor) {
                    tracked.lastSeenAnchorX = anchorX;
                    tracked.lastSeenAnchorY = anchorY;
                    tracked.hasLastSeenAnchor = true;
                }
                tracked.occlusionMissTicks = 0;
                tracked.wasOcclusionVisible = false;
                tracked.lastSeenAnchorX = anchorX;
                tracked.lastSeenAnchorY = anchorY;
                tracked.hasLastSeenAnchor = true;
                if (entityAlive) {
                    tracked.animation.recoverAppear();
                } else {
                    tracked.animation.triggerDisappear();
                }
            }

            if (tracked.animation.isFullyDead()) {
                tracked.animation = new MrAnimationController();
                tracked.layout = new MrWhipLayout();
            }

            tracked.animation.tick(deltaTime);
            tracked.animationTickedThisFrame = true;

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
            float contentScale = (float) (MrConstants.BASE_DISTANCE / Math.max(dist, 1.0));
            contentScale = Math.max(getMinCardScale(), Math.min(getMaxCardScale(), contentScale));

            boolean isFocused = stateMachine.isFocusing()
                    && uuid.equals(stateMachine.getFocusedEntityUuid());
            boolean isBackground = stateMachine.isFocusing() && !isFocused;
            float targetVisualScaleFactor = isFocused
                    ? MrConstants.FOCUS_SCALE
                    : isBackground ? MrConstants.BACKGROUND_SCALE : 1.0f;
            float targetVisualAlphaFactor = isBackground ? MrConstants.BACKGROUND_ALPHA_FACTOR : 1.0f;
            tracked.visualScaleFactor = smoothApproach(tracked.visualScaleFactor, targetVisualScaleFactor, deltaTime);
            tracked.visualAlphaFactor = smoothApproach(tracked.visualAlphaFactor, targetVisualAlphaFactor, deltaTime);
            float boardScale = contentScale * tracked.visualScaleFactor;
            boardScale = clampFocusedScale(boardScale, baseCardW, baseCardH, screenW, screenH);
            float visualContentScale = isBackground ? contentScale * tracked.visualScaleFactor : contentScale;
            float cardW = baseCardW * boardScale;
            boolean hasMainHand = entity.getMainHandItemId() != null && !entity.getMainHandItemId().isEmpty();
            boolean isFocusedThisFrame = isFocused;
            if (isFocusedThisFrame) {
                updateFocusedDetailText(tracked, entity, deltaTime);
            } else if (!isBackground) {
                resetFocusedDetailText(tracked);
            }
            float visibleDetailChars = isFocusedThisFrame ? tracked.focusedDetailVisibleChars : 0.0f;
            float cardH = computeCardHeight(baseCardH, boardScale, visualContentScale, cardW, isFocusedThisFrame, tracked.focusedDetailText, visibleDetailChars);

            TargetGeometry targetGeometry = computeTargetGeometry(anchorX, anchorY, boardScale, cardW, cardH, screenW, screenH, tracked);
            float targetCardX = targetGeometry.cardX;
            float targetCardY = targetGeometry.cardY;

            MrWhipLayout.LayoutResult layoutResult = tracked.layout.compute(
                    anchorX, anchorY,
                    targetCardX, targetCardY,
                    boardScale, cardW, cardH,
                    screenW, screenH, deltaTime, getSegmentLength(),
                    getCardDamping(), getCardMinDamping(), getCardMaxDamping()
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
            snap.connectorEdge = targetGeometry.connectorEdge;
            snap.connectorOnTopEdge = targetGeometry.connectorOnTopEdge;
            snap.connectorDirectionX = targetGeometry.connectorDirectionX;
            snap.connectorDirectionY = targetGeometry.connectorDirectionY;
            syncConnectorAfterCardMove(snap);
            snap.scale = boardScale;
            snap.contentScale = visualContentScale;
            snap.lineScale = visualContentScale;
            snap.alpha = Math.max(0.0f, Math.min(1.0f, alpha));
            snap.distanceFadeAlpha = distanceAlpha;
            snap.environmentAlphaFactor = environmentAlphaFactor;
            snap.appearProgress = tracked.animation.getAppearProgress();
            snap.disappearProgress = tracked.animation.getDisappearProgress();
            snap.isAlive = entityAlive;
            snap.isHostile = entity.isHostile();
            snap.isOcclusionVisible = occlusionVisible;
            snap.isFocused = isFocused;
            snap.isBackground = isBackground;
            snap.isManualFocus = false;
            snap.isBlockTarget = false;
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
            snap.relativeX = entity.getRelativeX();
            snap.relativeY = entity.getRelativeY();
            snap.relativeZ = entity.getRelativeZ();
            snap.eyeHeight = entity.getEyeHeight();
            snap.focusedDetailText = tracked.focusedDetailText;
            snap.focusedDetailVisibleChars = Math.round(tracked.focusedDetailVisibleChars);
            snap.focusedDetailOutputFinished = tracked.focusedDetailOutputFinished;
            applyFocusProgress(snap);
            precomputeVisuals(snap);

            tracked.lastSnapshot = snap;
            frameSnapshots.add(snap);
        }

        if (!closing && manualFocusTarget != null) {
            manualFocusTarget = refreshManualFocusTarget(manualFocusTarget);
            if (manualFocusTarget != null) {
                buildManualFocusSnapshot(manualFocusTarget, playerPos, deltaTime, projData, mvData, matrixProjectionAvailable, screenW, screenH, environmentAlphaFactor);
            }
        }

        if (shouldLogDebug) {
            debugLog("tick=" + debugTickCounter + " active=true state=" + stateMachine.getState() + " entities=" + entities.size() + " projected=" + frameSnapshots.size() + " projection_failed=" + projectionFailedCount + " projection_grace=" + projectionGraceCount + " occlusion_missing=" + occlusionMissingCount + " occlusion_grace=" + occlusionGraceCount + " disappearing=" + disappearingCount + " tracked=" + activeCards.size() + " screen=" + screenW + "x" + screenH);
        }

        if (stateMachine.isScanning() && focusInteractionEnabled && !closing) {
            scanningTimer += deltaTime;
            if (scanningTimer >= MrConstants.SCANNING_WARMUP) {
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
                        resetGazeTimers();
                    }
                } else {
                    decayGazeTimers(deltaTime);
                }
            }
        }

        if (!initialStaggerDone && scanningTimer >= MrConstants.SCANNING_WARMUP && !closing) {
            initialStaggerDone = true;
        }

        if (stateMachine.isFocusing() && !closing) {
            String focusedUuid = stateMachine.getFocusedEntityUuid();
            boolean focusedVisible = false;
            boolean gazeHeldOnFocusedTarget = isCrosshairHoldingFocusedTarget(focusedUuid);
            for (MrCardSnapshot snap : frameSnapshots) {
                if (snap.entityUuid.equals(focusedUuid)) {
                    focusedVisible = true;
                    break;
                }
            }

            if (!focusedVisible) {
                if (manualFocusTarget != null) {
                    if (isFocusedDetailOutputFinishedFromTracked(focusedUuid) && !gazeHeldOnFocusedTarget) {
                        focusExitCountdown -= deltaTime;
                        if (focusExitCountdown <= 0.0f) {
                            beginManualFocusDisappear();
                        }
                    } else {
                        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
                    }
                } else {
                    transitionFocusBackToScanning();
                }
            } else if (manualFocusTarget != null) {
                if (isFocusedDetailOutputFinished(frameSnapshots, focusedUuid) && !gazeHeldOnFocusedTarget) {
                    focusExitCountdown -= deltaTime;
                    aimWarmupTimer = 0.0f;
                    gazeTimer = 0.0f;
                    lastGazeUuid = null;
                    if (focusExitCountdown <= 0.0f) {
                        beginManualFocusDisappear();
                    }
                } else {
                    focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
                }

                if (stateMachine.isFocusing()) {
                    for (MrCardSnapshot snap : frameSnapshots) {
                        snap.isFocused = snap.entityUuid.equals(focusedUuid);
                        snap.isBackground = !snap.isFocused;
                    }
                }
            } else {
                if (isFocusedDetailOutputFinished(frameSnapshots, focusedUuid) && !gazeHeldOnFocusedTarget) {
                    focusExitCountdown -= deltaTime;
                    aimWarmupTimer = 0.0f;
                    gazeTimer = 0.0f;
                    lastGazeUuid = null;
                    if (focusExitCountdown <= 0.0f) {
                        transitionFocusBackToScanning();
                    }
                } else {
                    focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
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
                if (!tracked.suppressingForLimit) {
                    tracked.animation.tick(deltaTime);
                    tracked.animationTickedThisFrame = true;
                }
                if (tracked.animation.isFullyDead()) {
                    it.remove();
                } else if (tracked.lastSnapshot != null) {
                    MrCardSnapshot fadingSnapshot = tracked.lastSnapshot.copy();
                    float anchorX = fadingSnapshot.anchorX;
                    float anchorY = fadingSnapshot.anchorY;
                    TargetGeometry targetGeometry = computeTargetGeometry(
                            anchorX, anchorY,
                            fadingSnapshot.scale,
                            fadingSnapshot.cardWidth,
                            fadingSnapshot.cardHeight,
                            screenW, screenH,
                            tracked
                    );
                    MrWhipLayout.LayoutResult layoutResult = tracked.layout.compute(
                            anchorX, anchorY,
                            targetGeometry.cardX, targetGeometry.cardY,
                            fadingSnapshot.scale,
                            fadingSnapshot.cardWidth,
                            fadingSnapshot.cardHeight,
                            screenW, screenH, deltaTime, getSegmentLength(),
                            getCardDamping(), getCardMinDamping(), getCardMaxDamping()
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
                    fadingSnapshot.connectorEdgeRatio = targetGeometry.connectorEdgeRatio;
                    fadingSnapshot.connectorEdge = targetGeometry.connectorEdge;
                    fadingSnapshot.connectorOnTopEdge = targetGeometry.connectorOnTopEdge;
                    fadingSnapshot.connectorDirectionX = targetGeometry.connectorDirectionX;
                    fadingSnapshot.connectorDirectionY = targetGeometry.connectorDirectionY;
                    syncConnectorAfterCardMove(fadingSnapshot);
                    fadingSnapshot.alpha = fadeAlpha;
                    fadingSnapshot.environmentAlphaFactor = environmentAlphaFactor;
                    fadingSnapshot.appearProgress = tracked.animation.getAppearProgress();
                    fadingSnapshot.disappearProgress = tracked.animation.getDisappearProgress();
                    fadingSnapshot.isOcclusionVisible = false;
                    precomputeVisuals(fadingSnapshot);

                    tracked.lastSnapshot = fadingSnapshot;
                    frameSnapshots.add(fadingSnapshot);
                }
            }
        }

        applyPreLayoutDirectionSearch(frameSnapshots, deltaTime, screenW, screenH);
        for (MrCardSnapshot snap : frameSnapshots) {
            syncConnectorAfterCardMove(snap);
            applyFocusProgress(snap);
            precomputeVisuals(snap);
        }

        outputQueue.clear();
        for (MrCardSnapshot snap : frameSnapshots) {
            outputQueue.offer(snap);
        }
    }

    private boolean isEntityInsideCurrentFov(PositionData playerPos, NearbyEntityData entity, int screenW, int screenH) {
        float currentFov = 70.0f;
        if (playerStateProvider != null) {
            try {
                currentFov = playerStateProvider.getCurrentDynamicFov();
            } catch (Exception ignored) {
            }
        }
        if (currentFov <= 10.0f || currentFov > 180.0f) currentFov = 70.0f;
        double verticalFov = Math.toRadians(currentFov);
        double horizontalFov = 2.0 * Math.atan(Math.tan(verticalFov * 0.5) * ((double) screenW / Math.max(1, screenH)));
        double horizontalLimit = Math.toDegrees(horizontalFov * 0.5) * 1.08;
        if (Math.abs(entity.getHorizontalAngle()) > horizontalLimit) return false;
        double horizontalDistance = Math.max(0.1, Math.sqrt(entity.getRelativeX() * entity.getRelativeX() + entity.getRelativeZ() * entity.getRelativeZ()));
        double headOffset = Math.min(0.18, Math.max(0.04, entity.getEyeHeight() * 0.08));
        double targetHeightFromEye = entity.getRelativeY() + entity.getEyeHeight() + headOffset - 1.62;
        double targetPitchDeg = Math.toDegrees(Math.atan2(targetHeightFromEye, horizontalDistance)) + playerPos.getPitch();
        return Math.abs(targetPitchDeg) <= Math.toDegrees(verticalFov * 0.5) * 1.18;
    }

    private float[] projectFromPlayerView(PositionData playerPos, NearbyEntityData entity, int screenW, int screenH) {
        return projectFromPlayerView(playerPos, entity, screenW, screenH, false);
    }

    private float[] projectFromPlayerView(PositionData playerPos, NearbyEntityData entity, int screenW, int screenH, boolean allowOffscreen) {
        float currentFov = 70.0f;
        if (playerStateProvider != null) {
            try {
                currentFov = playerStateProvider.getCurrentDynamicFov();
            } catch (Exception ignored) {
            }
        }
        if (currentFov <= 10.0f || currentFov > 180.0f) currentFov = 70.0f;
        double verticalFov = Math.toRadians(currentFov);
        double horizontalFov = 2.0 * Math.atan(Math.tan(verticalFov * 0.5) * ((double) screenW / Math.max(1, screenH)));
        double relativeYawRad = Math.toRadians(entity.getHorizontalAngle());
        double ndcX = Math.tan(relativeYawRad) / Math.tan(horizontalFov * 0.5);
        double horizontalDistance = Math.max(0.1, Math.sqrt(entity.getRelativeX() * entity.getRelativeX() + entity.getRelativeZ() * entity.getRelativeZ()));
        double headOffset = Math.min(0.18, Math.max(0.04, entity.getEyeHeight() * 0.08));
        double targetHeightFromEye = entity.getRelativeY() + entity.getEyeHeight() + headOffset - 1.62;
        double targetPitchRad = Math.atan2(targetHeightFromEye, horizontalDistance);
        double relativePitchRad = targetPitchRad + Math.toRadians(playerPos.getPitch());
        double ndcY = Math.tan(relativePitchRad) / Math.tan(verticalFov * 0.5);
        if (!allowOffscreen && (ndcX < -1.08 || ndcX > 1.08 || ndcY < -1.08 || ndcY > 1.08)) return null;
        if (allowOffscreen) {
            ndcX = Math.max(-1.8, Math.min(1.8, ndcX));
            ndcY = Math.max(-1.8, Math.min(1.8, ndcY));
        }
        float screenX = (float) ((ndcX * 0.5 + 0.5) * screenW);
        float screenY = (float) ((0.5 - ndcY * 0.5) * screenH);
        return new float[]{screenX, screenY};
    }

    private Set<String> selectVisibleCardUuids(List<NearbyEntityData> entities, PositionData playerPos, float[] projData, float[] mvData, boolean matrixProjectionAvailable, int screenW, int screenH) {
        Set<String> selected = new HashSet<>();
        String crosshairUuid = closing ? null : environmentProvider.getCrosshairTargetEntityUuid();
        if (crosshairUuid != null && !crosshairUuid.isEmpty()) {
            for (NearbyEntityData entity : entities) {
                String uuid = entity.getUuid();
                if (!crosshairUuid.equals(uuid)) continue;
                if (isSelectableCardEntity(entity, playerPos, projData, mvData, matrixProjectionAvailable, screenW, screenH)) {
                    selected.add(uuid);
                }
                break;
            }
        }
        for (NearbyEntityData entity : entities) {
            if (selected.size() >= MrConstants.MAX_CARDS) break;
            String uuid = entity.getUuid();
            if (uuid == null || uuid.isEmpty() || selected.contains(uuid)) continue;
            if (!isSelectableCardEntity(entity, playerPos, projData, mvData, matrixProjectionAvailable, screenW, screenH)) continue;
            selected.add(uuid);
        }
        return selected;
    }

    private boolean isSelectableCardEntity(NearbyEntityData entity, PositionData playerPos, float[] projData, float[] mvData, boolean matrixProjectionAvailable, int screenW, int screenH) {
        if (entity == null || entity.getUuid() == null || entity.getUuid().isEmpty()) return false;
        if (entity.getHealth() <= 0.0f || !entity.isOcclusionVisible()) return false;
        if (!isEntityInsideCurrentFov(playerPos, entity, screenW, screenH)) return false;
        return projectEntityAnchor(playerPos, entity, projData, mvData, matrixProjectionAvailable, screenW, screenH) != null;
    }

    private void suppressUnselectedCards(Set<String> selectedUuids) {
        for (Map.Entry<String, TrackedCard> entry : activeCards.entrySet()) {
            TrackedCard tracked = entry.getValue();
            boolean selected = selectedUuids.contains(entry.getKey());
            tracked.suppressingForLimit = !selected;
            if (!selected) {
                if (manualFocusTarget != null && entry.getKey().equals(manualFocusTarget.getUuid())) continue;
                tracked.animation.triggerDisappear();
            }
        }
    }

    private void buildVisibleEntityScreenRects(List<NearbyEntityData> entities, PositionData playerPos, float[] projData, float[] mvData, boolean matrixProjectionAvailable, Set<String> selectedUuids, int screenW, int screenH, List<EntityScreenRect> rects) {
        rects.clear();
        for (NearbyEntityData entity : entities) {
            String uuid = entity.getUuid();
            if (uuid == null || uuid.isEmpty()) continue;
            if (!selectedUuids.contains(uuid)) continue;
            if (entity.getHealth() <= 0.0f || !entity.isOcclusionVisible()) continue;
            float[] screenPos = projectEntityAnchor(playerPos, entity, projData, mvData, matrixProjectionAvailable, screenW, screenH);
            if (screenPos == null) continue;
            float width = Math.max(12.0f, Math.min(48.0f, entity.getBoundingHeight() * 8.0f));
            float height = Math.max(18.0f, Math.min(72.0f, entity.getBoundingHeight() * 18.0f));
            float left = screenPos[0] - width * 0.5f;
            float top = screenPos[1] - height * 0.45f;
            float priority = (entity.isHostile() ? 1.6f : 1.0f) * (1.0f + Math.max(0.0f, (float) MrConstants.MR_RANGE - (float) entity.getDistance()) / (float) MrConstants.MR_RANGE);
            rects.add(new EntityScreenRect(uuid, left, top, left + width, top + height, priority));
        }
    }

    private float[] projectEntityAnchor(PositionData playerPos, NearbyEntityData entity, float[] projData, float[] mvData, boolean matrixProjectionAvailable, int screenW, int screenH) {
        boolean entityInView = isEntityInsideCurrentFov(playerPos, entity, screenW, screenH);
        if (!entityInView) return null;
        double entityWorldX = playerPos.getX() + entity.getRelativeX();
        double entityWorldY = playerPos.getY() + entity.getRelativeY() + entity.getBoundingHeight() + 0.2;
        double entityWorldZ = playerPos.getZ() + entity.getRelativeZ();
        float[] screenPos = null;
        if (matrixProjectionAvailable) {
            screenPos = MrProjector.project(entityWorldX, entityWorldY, entityWorldZ, mvData, projData, screenW, screenH);
        }
        if (screenPos == null) {
            screenPos = projectFromPlayerView(playerPos, entity, screenW, screenH);
        }
        return screenPos;
    }

    private void applyPreLayoutDirectionSearch(List<MrCardSnapshot> snapshots, float deltaTime, int screenW, int screenH) {
        for (MrCardSnapshot snap : snapshots) {
            TrackedCard tracked = activeCards.get(snap.entityUuid);
            if (tracked != null) {
                tracked.layoutOffsetX = smoothApproach(tracked.layoutOffsetX, 0.0f, deltaTime);
                tracked.layoutOffsetY = smoothApproach(tracked.layoutOffsetY, 0.0f, deltaTime);
            }
            syncConnectorAfterCardMove(snap);
        }
    }

    private void chooseBestCandidatePosition(MrCardSnapshot snap, float deltaTime, List<MrCardSnapshot> placed, List<EntityScreenRect> entityRects, int screenW, int screenH) {
        TrackedCard tracked = activeCards.get(snap.entityUuid);
        float originalX = snap.cardX;
        float originalY = snap.cardY;
        float ratio = snap.connectorEdgeRatio;
        float originalConnectorX;
        float originalConnectorY;
        if (snap.connectorEdge == 2 || snap.connectorEdge == 3) {
            originalConnectorX = snap.connectorEdge == 2 ? originalX : originalX + snap.cardWidth;
            originalConnectorY = originalY + snap.cardHeight * ratio;
        } else {
            originalConnectorX = originalX + snap.cardWidth * ratio;
            originalConnectorY = snap.connectorEdge == 0 ? originalY : originalY + snap.cardHeight;
        }
        float originalAcX = originalConnectorX - snap.anchorX;
        float originalAcY = originalConnectorY - snap.anchorY;
        float originalAcLength = (float) Math.sqrt(originalAcX * originalAcX + originalAcY * originalAcY);
        float originalAcDirectionX = originalAcLength > 0.001f ? originalAcX / originalAcLength : snap.connectorDirectionX;
        float originalAcDirectionY = originalAcLength > 0.001f ? originalAcY / originalAcLength : snap.connectorDirectionY;
        LayoutContext context = new LayoutContext(placed, entityRects, screenW, screenH, originalX, originalY, snap.jointX, snap.jointY, snap.connectorDirectionX, snap.connectorDirectionY, snap.entityUuid);

        LayoutCandidate defaultCandidate = buildLayoutCandidate(snap, snap.jointX, snap.jointY, originalX, originalY, snap.connectorDirectionX, snap.connectorDirectionY, snap.connectorOnTopEdge, context, FULL_LAYOUT_EVALUATION);
        if (defaultCandidate.acceptable) return;

        LayoutCandidate bestFallback = defaultCandidate;
        float segmentLength = getSegmentLength() * snap.scale;
        AbDirection[] abDirections = prioritizeAbDirections(AB_DIRECTIONS, snap.jointX - snap.anchorX, snap.jointY - snap.anchorY);
        for (AbDirection abDirection : abDirections) {
            float jointX = snap.anchorX + abDirection.x * segmentLength;
            float jointY = snap.anchorY + abDirection.y * segmentLength;
            BcDirection[] bcDirections = buildBcOptionsForAb(abDirection);
            bcDirections = prioritizeBcDirections(bcDirections, snap.connectorDirectionX, snap.connectorDirectionY);
            for (BcDirection bcDirection : bcDirections) {
                float connectorX = jointX + bcDirection.x * segmentLength;
                float connectorY = jointY + bcDirection.y * segmentLength;
                boolean connectorOnTop = bcDirection.y >= 0.0f;
                float cardX = connectorX - snap.cardWidth * ratio;
                float cardY = connectorOnTop ? connectorY : connectorY - snap.cardHeight;
                LayoutCandidate candidate = buildLayoutCandidate(snap, jointX, jointY, cardX, cardY, bcDirection.x, bcDirection.y, connectorOnTop, context, FULL_LAYOUT_EVALUATION);
                if (candidate.acceptable) {
                    applyLayoutCandidate(snap, tracked, candidate, originalX, originalY, deltaTime);
                    return;
                }
                if (candidate.score < bestFallback.score) bestFallback = candidate;
            }
        }

        float extensionBaseLength = Math.max(originalAcLength, segmentLength * 2.0f);
        for (float factor : AC_EXTENSION_FACTORS) {
            float length = extensionBaseLength * factor;
            float connectorX = snap.anchorX + originalAcDirectionX * length;
            float connectorY = snap.anchorY + originalAcDirectionY * length;
            boolean connectorOnTop = originalAcDirectionY >= 0.0f;
            float cardX = connectorX - snap.cardWidth * ratio;
            float cardY = connectorOnTop ? connectorY : connectorY - snap.cardHeight;
            LayoutCandidate candidate = buildLayoutCandidate(snap, snap.jointX, snap.jointY, cardX, cardY, originalAcDirectionX, originalAcDirectionY, connectorOnTop, context, CARD_ONLY_LAYOUT_EVALUATION);
            if (candidate.acceptable) {
                applyLayoutCandidate(snap, tracked, candidate, originalX, originalY, deltaTime);
                return;
            }
            if (candidate.score < bestFallback.score) bestFallback = candidate;
        }

        applyLayoutCandidate(snap, tracked, bestFallback, originalX, originalY, deltaTime);
    }

    private void applyLayoutCandidate(MrCardSnapshot snap, TrackedCard tracked, LayoutCandidate candidate, float originalX, float originalY, float deltaTime) {
        if (tracked == null) {
            snap.jointX = candidate.jointX;
            snap.jointY = candidate.jointY;
            snap.cardX = candidate.cardX;
            snap.cardY = candidate.cardY;
            syncConnectorAfterCardMove(snap);
            snap.connectorDirectionX = candidate.connectorDirectionX;
            snap.connectorDirectionY = candidate.connectorDirectionY;
            snap.connectorEdge = candidate.connectorEdge;
            snap.connectorOnTopEdge = candidate.connectorOnTopEdge;
            return;
        }
        float targetOffsetX = candidate.cardX - originalX;
        float targetOffsetY = candidate.cardY - originalY;
        tracked.layoutOffsetX = smoothApproach(tracked.layoutOffsetX, targetOffsetX, deltaTime);
        tracked.layoutOffsetY = smoothApproach(tracked.layoutOffsetY, targetOffsetY, deltaTime);
        snap.jointX = candidate.jointX;
        snap.jointY = candidate.jointY;
        snap.cardX = originalX + tracked.layoutOffsetX;
        snap.cardY = originalY + tracked.layoutOffsetY;
        syncConnectorAfterCardMove(snap);
        snap.connectorDirectionX = candidate.connectorDirectionX;
        snap.connectorDirectionY = candidate.connectorDirectionY;
        snap.connectorEdge = candidate.connectorEdge;
        snap.connectorOnTopEdge = candidate.connectorOnTopEdge;
        float magnitude = (float) Math.sqrt(targetOffsetX * targetOffsetX + targetOffsetY * targetOffsetY);
        if (magnitude > 0.001f) {
            tracked.lastLayoutDirectionX = targetOffsetX / magnitude;
            tracked.lastLayoutDirectionY = targetOffsetY / magnitude;
            tracked.hasLastLayoutDirection = true;
        }
    }


    private AbDirection[] prioritizeAbDirections(AbDirection[] directions, float currentX, float currentY) {
        AbDirection[] ordered = Arrays.copyOf(directions, directions.length);
        float length = (float) Math.sqrt(currentX * currentX + currentY * currentY);
        if (length <= 0.001f) return ordered;
        float dirX = currentX / length;
        float dirY = currentY / length;
        Arrays.sort(ordered, Comparator.comparingDouble(d -> -(d.x * dirX + d.y * dirY)));
        return ordered;
    }

    private BcDirection[] prioritizeBcDirections(BcDirection[] directions, float currentX, float currentY) {
        BcDirection[] ordered = Arrays.copyOf(directions, directions.length);
        float length = (float) Math.sqrt(currentX * currentX + currentY * currentY);
        if (length <= 0.001f) return ordered;
        float dirX = currentX / length;
        float dirY = currentY / length;
        Arrays.sort(ordered, Comparator.comparingDouble(d -> -(d.x * dirX + d.y * dirY)));
        return ordered;
    }

    private BcDirection[] buildBcOptionsForAb(AbDirection abDirection) {
        float theta = (float) Math.toRadians(MrConstants.BC_REST_ANGLE_DEGREES);
        float cos = (float) Math.cos(theta);
        float sin = (float) Math.sin(theta);
        if (Math.abs(abDirection.x) > Math.abs(abDirection.y)) {
            float horizontal = abDirection.x >= 0.0f ? 1.0f : -1.0f;
            return new BcDirection[]{
                    new BcDirection(horizontal * cos, -sin),
                    new BcDirection(horizontal * cos, sin)
            };
        }
        float vertical = abDirection.y >= 0.0f ? 1.0f : -1.0f;
        return new BcDirection[]{
                new BcDirection(-sin, vertical * cos),
                new BcDirection(sin, vertical * cos)
        };
    }

    private LayoutCandidate buildLayoutCandidate(MrCardSnapshot snap, float jointX, float jointY, float cardX, float cardY, float directionX, float directionY, boolean connectorOnTop, LayoutContext context, LayoutEvaluationOptions options) {
        int edge = connectorOnTop ? 0 : 1;
        float connectorX = cardX + snap.cardWidth * snap.connectorEdgeRatio;
        float connectorY = connectorOnTop ? cardY : cardY + snap.cardHeight;
        CandidateEvaluation evaluation = evaluateLayoutCandidate(snap, jointX, jointY, cardX, cardY, connectorX, connectorY, directionX, directionY, context, options);
        return new LayoutCandidate(jointX, jointY, cardX, cardY, directionX, directionY, edge, connectorOnTop, evaluation.score, evaluation.acceptable);
    }

    private CandidateEvaluation evaluateLayoutCandidate(MrCardSnapshot snap, float jointX, float jointY, float cardX, float cardY, float connectorX, float connectorY, float directionX, float directionY, LayoutContext context, LayoutEvaluationOptions options) {
        float score = 0.0f;
        for (MrCardSnapshot other : context.placed) {
            score += computeOverlapArea(cardX, cardY, snap.cardWidth, snap.cardHeight, other.cardX, other.cardY, other.cardWidth, other.cardHeight) * 6.0f;
        }
        for (EntityScreenRect rect : context.entityRects) {
            if (snap.entityUuid != null && snap.entityUuid.equals(rect.uuid)) continue;
            score += computeOverlapArea(cardX, cardY, snap.cardWidth, snap.cardHeight, rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top) * rect.priorityWeight * 10.0f;
        }
        score += computeOutOfScreenPenalty(cardX, cardY, snap.cardWidth, snap.cardHeight, context.screenW, context.screenH);
        float dx = cardX - context.originalX;
        float dy = cardY - context.originalY;
        score += (float) Math.sqrt(dx * dx + dy * dy) * 0.08f;
        float jointDx = jointX - context.originalJointX;
        float jointDy = jointY - context.originalJointY;
        score += (float) Math.sqrt(jointDx * jointDx + jointDy * jointDy) * 0.12f;
        float directionDot = directionX * context.originalDirectionX + directionY * context.originalDirectionY;
        score += Math.max(0.0f, 1.0f - directionDot) * snap.cardWidth * snap.cardHeight * 0.15f;

        if (options.checkLines) {
            Segment candidateAb = new Segment(snap.anchorX, snap.anchorY, jointX, jointY);
            Segment candidateBc = new Segment(jointX, jointY, connectorX, connectorY);
            for (MrCardSnapshot other : context.placed) {
                SegmentPair otherSegments = buildSegments(other);
                if (segmentsIntersect(candidateAb, otherSegments.ab)
                        || segmentsIntersect(candidateAb, otherSegments.bc)
                        || segmentsIntersect(candidateBc, otherSegments.ab)
                        || segmentsIntersect(candidateBc, otherSegments.bc)) {
                    score += snap.cardWidth * snap.cardHeight * 2.0f;
                }
            }
            for (EntityScreenRect rect : context.entityRects) {
                if (snap.entityUuid != null && snap.entityUuid.equals(rect.uuid)) continue;
                if (segmentIntersectsRect(candidateAb, rect) || segmentIntersectsRect(candidateBc, rect)) {
                    score += snap.cardWidth * snap.cardHeight * rect.priorityWeight;
                }
            }
        }
        return new CandidateEvaluation(score, score <= LAYOUT_ACCEPTABLE_SCORE);
    }

    private void buildEntityScreenRects(List<MrCardSnapshot> snapshots, List<EntityScreenRect> rects) {
        rects.clear();
        for (MrCardSnapshot snapshot : snapshots) {
            float width = Math.max(12.0f, snapshot.cardWidth * 0.22f);
            float height = Math.max(18.0f, Math.min(72.0f, snapshot.cardHeight * 0.9f + snapshot.eyeHeight * 18.0f));
            float left = snapshot.anchorX - width * 0.5f;
            float top = snapshot.anchorY - height * 0.45f;
            float priority = (snapshot.isFocused ? 3.0f : 1.0f) * (snapshot.isHostile ? 1.6f : 1.0f) * (1.0f + Math.max(0.0f, (float) MrConstants.MR_RANGE - snapshot.distance) / (float) MrConstants.MR_RANGE);
            rects.add(new EntityScreenRect(snapshot.entityUuid, left, top, left + width, top + height, priority));
        }
    }

    private SegmentPair buildSegments(MrCardSnapshot snap) {
        return new SegmentPair(
                new Segment(snap.anchorX, snap.anchorY, snap.jointX, snap.jointY),
                new Segment(snap.jointX, snap.jointY, snap.connectorX, snap.connectorY)
        );
    }

    private boolean segmentIntersectsRect(Segment segment, EntityScreenRect rect) {
        if (pointInRect(segment.x1, segment.y1, rect) || pointInRect(segment.x2, segment.y2, rect)) return true;
        return segmentsIntersect(segment, new Segment(rect.left, rect.top, rect.right, rect.top))
                || segmentsIntersect(segment, new Segment(rect.right, rect.top, rect.right, rect.bottom))
                || segmentsIntersect(segment, new Segment(rect.right, rect.bottom, rect.left, rect.bottom))
                || segmentsIntersect(segment, new Segment(rect.left, rect.bottom, rect.left, rect.top));
    }

    private boolean pointInRect(float x, float y, EntityScreenRect rect) {
        return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
    }

    private boolean segmentsIntersect(Segment a, Segment b) {
        float d1 = direction(a.x1, a.y1, a.x2, a.y2, b.x1, b.y1);
        float d2 = direction(a.x1, a.y1, a.x2, a.y2, b.x2, b.y2);
        float d3 = direction(b.x1, b.y1, b.x2, b.y2, a.x1, a.y1);
        float d4 = direction(b.x1, b.y1, b.x2, b.y2, a.x2, a.y2);
        if (((d1 > 0.0f && d2 < 0.0f) || (d1 < 0.0f && d2 > 0.0f))
                && ((d3 > 0.0f && d4 < 0.0f) || (d3 < 0.0f && d4 > 0.0f))) return true;
        return d1 == 0.0f && onSegment(a.x1, a.y1, a.x2, a.y2, b.x1, b.y1)
                || d2 == 0.0f && onSegment(a.x1, a.y1, a.x2, a.y2, b.x2, b.y2)
                || d3 == 0.0f && onSegment(b.x1, b.y1, b.x2, b.y2, a.x1, a.y1)
                || d4 == 0.0f && onSegment(b.x1, b.y1, b.x2, b.y2, a.x2, a.y2);
    }

    private float direction(float ax, float ay, float bx, float by, float cx, float cy) {
        return (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
    }

    private boolean onSegment(float ax, float ay, float bx, float by, float cx, float cy) {
        return cx >= Math.min(ax, bx) && cx <= Math.max(ax, bx) && cy >= Math.min(ay, by) && cy <= Math.max(ay, by);
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

    private boolean isFocusedDetailOutputFinishedFromTracked(String focusedUuid) {
        TrackedCard tracked = activeCards.get(focusedUuid);
        return tracked != null && tracked.focusedDetailOutputFinished;
    }

    private MrManualFocusTargetData refreshManualFocusTarget(MrManualFocusTargetData target) {
        if (target == null || environmentProvider == null) return target;
        MrManualFocusTargetData refreshed = environmentProvider.refreshManualFocusTarget(target, MrConstants.MR_RANGE);
        if (refreshed == null || refreshed.getUuid() == null || !refreshed.getUuid().equals(target.getUuid())) return target;
        return refreshed;
    }

    private void buildManualFocusSnapshot(MrManualFocusTargetData target, PositionData playerPos, float deltaTime, float[] projData, float[] mvData, boolean matrixProjectionAvailable, int screenW, int screenH, float environmentAlphaFactor) {
        String uuid = target.getUuid();
        TrackedCard tracked = activeCards.get(uuid);
        double worldX = target.getWorldX();
        double worldY = target.getWorldY();
        double worldZ = target.getWorldZ();
        float[] screenPos = null;
        if (matrixProjectionAvailable) {
            screenPos = MrProjector.projectUnclamped(worldX, worldY, worldZ, mvData, projData, screenW, screenH);
        }
        if (screenPos == null) {
            screenPos = projectManualFocusFromPlayerView(playerPos, target, screenW, screenH, true);
        }
        if (screenPos == null) return;

        float anchorX = screenPos[0];
        float anchorY = screenPos[1];
        if (tracked == null) {
            tracked = new TrackedCard(uuid);
            activeCards.put(uuid, tracked);
        }
        currentUuids.add(uuid);
        tracked.suppressingForLimit = false;
        tracked.projectionMissTicks = 0;
        tracked.occlusionMissTicks = 0;
        tracked.wasOcclusionVisible = true;
        tracked.lastSeenAnchorX = anchorX;
        tracked.lastSeenAnchorY = anchorY;
        tracked.hasLastSeenAnchor = true;
        tracked.animation.recoverAppear();
        tracked.animation.tick(deltaTime);
        tracked.animationTickedThisFrame = true;
        updateFixedFocusedDetailText(tracked, target.getDetailText(), deltaTime);

        float dist = (float) Math.max(1.0, target.getDistance());
        float baseCardW = computeBaseCardWidth(screenW);
        float baseCardH = computeBaseCardHeight(screenH);
        float contentScale = (float) (MrConstants.BASE_DISTANCE / dist);
        contentScale = Math.max(getMinCardScale(), Math.min(getMaxCardScale(), contentScale));
        tracked.visualScaleFactor = smoothApproach(tracked.visualScaleFactor, MrConstants.FOCUS_SCALE, deltaTime);
        tracked.visualAlphaFactor = smoothApproach(tracked.visualAlphaFactor, 1.0f, deltaTime);
        float boardScale = clampFocusedScale(contentScale * tracked.visualScaleFactor, baseCardW, baseCardH, screenW, screenH);
        float cardW = baseCardW * boardScale;
        float cardH = computeCardHeight(baseCardH, boardScale, contentScale, cardW, true, tracked.focusedDetailText, tracked.focusedDetailVisibleChars);

        TargetGeometry targetGeometry = computeTargetGeometry(anchorX, anchorY, boardScale, cardW, cardH, screenW, screenH, tracked);
        MrWhipLayout.LayoutResult layoutResult = tracked.layout.compute(
                anchorX, anchorY,
                targetGeometry.cardX, targetGeometry.cardY,
                boardScale, cardW, cardH,
                screenW, screenH, deltaTime, getSegmentLength(),
                getCardDamping(), getCardMinDamping(), getCardMaxDamping()
        );

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
        snap.connectorEdge = targetGeometry.connectorEdge;
        snap.connectorOnTopEdge = targetGeometry.connectorOnTopEdge;
        snap.connectorDirectionX = targetGeometry.connectorDirectionX;
        snap.connectorDirectionY = targetGeometry.connectorDirectionY;
        syncConnectorAfterCardMove(snap);
        snap.scale = boardScale;
        snap.contentScale = contentScale;
        snap.lineScale = contentScale;
        snap.alpha = Math.max(0.0f, Math.min(1.0f, MrConstants.BASE_ALPHA * tracked.animation.getAnimationAlpha() * tracked.visualAlphaFactor));
        snap.distanceFadeAlpha = MrConstants.BASE_ALPHA;
        snap.environmentAlphaFactor = environmentAlphaFactor;
        snap.appearProgress = tracked.animation.getAppearProgress();
        snap.disappearProgress = tracked.animation.getDisappearProgress();
        snap.isAlive = true;
        snap.isHostile = target.isHostile();
        snap.isOcclusionVisible = target.isOcclusionVisible();
        snap.isFocused = manualFocusTarget != null && uuid.equals(manualFocusTarget.getUuid());
        snap.isBackground = false;
        snap.isManualFocus = true;
        snap.isBlockTarget = target.getType() == MrManualFocusTargetData.TargetType.BLOCK;
        snap.hasMainHandItem = target.getMainHandItemId() != null && !target.getMainHandItemId().isEmpty();
        snap.displayName = safeText(target.getDisplayName(), safeText(target.getRegistryId(), snap.isBlockTarget ? "Block Target" : "Manual Target"));
        snap.entityId = safeText(target.getRegistryId(), snap.isBlockTarget ? "minecraft:block" : "minecraft:entity");
        snap.mainHandItemId = target.getMainHandItemId();
        snap.entityUuid = uuid;
        snap.health = target.getHealth();
        snap.maxHealth = target.getMaxHealth();
        snap.distance = dist;
        snap.attackDamage = target.getAttackDamage();
        snap.armorValue = target.getArmorValue();
        snap.relativeX = target.getRelativeX();
        snap.relativeY = target.getRelativeY();
        snap.relativeZ = target.getRelativeZ();
        snap.worldX = target.getWorldX();
        snap.worldY = target.getWorldY();
        snap.worldZ = target.getWorldZ();
        snap.hasWorldAnchor = true;
        snap.fixedWorldAnchor = target.getType() == MrManualFocusTargetData.TargetType.BLOCK;
        snap.eyeHeight = target.getEyeHeight();
        snap.focusedDetailText = tracked.focusedDetailText;
        snap.focusedDetailVisibleChars = Math.round(tracked.focusedDetailVisibleChars);
        snap.focusedDetailOutputFinished = tracked.focusedDetailOutputFinished;
        applyFocusProgress(snap);
        precomputeVisuals(snap);
        tracked.lastSnapshot = snap;
        frameSnapshots.add(snap);
    }

    private float[] projectManualFocusFromPlayerView(PositionData playerPos, MrManualFocusTargetData target, int screenW, int screenH, boolean allowOffscreen) {
        float currentFov = 70.0f;
        if (playerStateProvider != null) {
            try {
                currentFov = playerStateProvider.getCurrentDynamicFov();
            } catch (Exception ignored) {
            }
        }
        if (currentFov <= 10.0f || currentFov > 180.0f) currentFov = 70.0f;
        double verticalFov = Math.toRadians(currentFov);
        double horizontalFov = 2.0 * Math.atan(Math.tan(verticalFov * 0.5) * ((double) screenW / Math.max(1, screenH)));
        double relX = target.getWorldX() - playerPos.getX();
        double relY = target.getWorldY() - playerPos.getY();
        double relZ = target.getWorldZ() - playerPos.getZ();
        double horizontalDistance = Math.max(0.1, Math.sqrt(relX * relX + relZ * relZ));
        double targetYawRad = Math.atan2(relZ, relX) - Math.PI * 0.5;
        double relativeYawRad = wrapRadians(targetYawRad - Math.toRadians(playerPos.getYaw()));
        double ndcX = Math.tan(relativeYawRad) / Math.tan(horizontalFov * 0.5);
        double targetPitchRad = Math.atan2(relY - 1.62, horizontalDistance);
        double relativePitchRad = targetPitchRad + Math.toRadians(playerPos.getPitch());
        double ndcY = Math.tan(relativePitchRad) / Math.tan(verticalFov * 0.5);
        if (!allowOffscreen && (ndcX < -1.08 || ndcX > 1.08 || ndcY < -1.08 || ndcY > 1.08)) return null;
        if (allowOffscreen) {
            ndcX = Math.max(-2.2, Math.min(2.2, ndcX));
            ndcY = Math.max(-2.2, Math.min(2.2, ndcY));
        }
        float screenX = (float) ((ndcX * 0.5 + 0.5) * screenW);
        float screenY = (float) ((0.5 - ndcY * 0.5) * screenH);
        return new float[]{screenX, screenY};
    }

    private double wrapRadians(double value) {
        while (value <= -Math.PI) value += Math.PI * 2.0;
        while (value > Math.PI) value -= Math.PI * 2.0;
        return value;
    }

    private void applyFocusProgress(MrCardSnapshot snap) {
        snap.focusExitProgress = computeFocusExitProgress(snap);
        snap.focusExitProgressActive = snap.isFocused
                && stateMachine.isFocusing()
                && !closing
                && snap.focusedDetailOutputFinished;
        if (stateMachine.isFocusing() && snap.isFocused && !closing) {
            snap.focusProgress = 1.0f;
            snap.focusProgressActive = !snap.focusExitProgressActive;
            return;
        }
        if (manualFocusPreviewTarget != null && snap.entityUuid != null && snap.entityUuid.equals(manualFocusPreviewTarget.getUuid())) {
            snap.focusProgress = Math.max(0.0f, Math.min(1.0f, manualFocusPreviewProgress));
            snap.focusProgressActive = snap.focusProgress > 0.0f;
            snap.focusExitProgressActive = false;
            return;
        }
        if (!stateMachine.isScanning() || closing || snap.entityUuid == null || !snap.entityUuid.equals(lastGazeUuid)) {
            snap.focusProgress = 0.0f;
            snap.focusProgressActive = false;
            return;
        }
        float progress = MrConstants.GAZE_FOCUS_DURATION > 0.0f ? gazeTimer / MrConstants.GAZE_FOCUS_DURATION : 1.0f;
        snap.focusProgress = Math.max(0.0f, Math.min(1.0f, progress));
        snap.focusProgressActive = snap.focusProgress > 0.0f;
    }

    private float computeFocusExitProgress(MrCardSnapshot snap) {
        if (!stateMachine.isFocusing() || closing || !snap.isFocused || !snap.focusedDetailOutputFinished) return 0.0f;
        if (MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS <= 0.0f) return 1.0f;
        return Math.max(0.0f, Math.min(1.0f, 1.0f - focusExitCountdown / MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS));
    }

    private void updateFocusedDetailText(TrackedCard tracked, NearbyEntityData entity, float deltaTime) {
        String detailText = buildFocusedDetailText(entity);
        if (!detailText.equals(tracked.focusedDetailText)) {
            tracked.focusedDetailText = detailText;
            tracked.focusedDetailVisibleChars = Math.min(tracked.focusedDetailVisibleChars, tracked.focusedDetailText.length());
            tracked.focusedDetailOutputFinished = tracked.focusedDetailText.isEmpty() || tracked.focusedDetailVisibleChars >= tracked.focusedDetailText.length();
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
        tracked.fixedDetailText = null;
        tracked.focusedDetailVisibleChars = 0.0f;
        tracked.focusedDetailOutputFinished = false;
    }

    private void updateFixedFocusedDetailText(TrackedCard tracked, String detailText, float deltaTime) {
        String safeDetailText = detailText != null && !detailText.isEmpty() ? detailText : "NO DETAIL";
        if (!safeDetailText.equals(tracked.focusedDetailText)) {
            tracked.fixedDetailText = safeDetailText;
            tracked.focusedDetailText = safeDetailText;
            tracked.focusedDetailVisibleChars = Math.min(tracked.focusedDetailVisibleChars, tracked.focusedDetailText.length());
            tracked.focusedDetailOutputFinished = tracked.focusedDetailText.isEmpty() || tracked.focusedDetailVisibleChars >= tracked.focusedDetailText.length();
        }
        if (!tracked.focusedDetailOutputFinished) {
            tracked.focusedDetailVisibleChars += deltaTime * MrConstants.FOCUS_TEXT_CHARS_PER_SECOND;
            if (tracked.focusedDetailVisibleChars >= tracked.focusedDetailText.length()) {
                tracked.focusedDetailVisibleChars = tracked.focusedDetailText.length();
                tracked.focusedDetailOutputFinished = true;
            }
        }
    }

    private String buildFocusedDetailText(NearbyEntityData entity) {
        if (entity.getDetailText() != null && !entity.getDetailText().isEmpty()) {
            return entity.getDetailText();
        }
        StringBuilder builder = new StringBuilder();
        builder.append("ENTITY ").append(safeText(entity.getDisplayName(), entity.getEntityId()));
        builder.append("\nID ").append(safeText(entity.getEntityId(), "unknown"));
        builder.append("\nUUID ").append(shortUuid(entity.getUuid()));
        builder.append("\nHP ").append(String.format("%.1f/%.1f", entity.getHealth(), entity.getMaxHealth()));
        builder.append("  DIST ").append(String.format("%.1fm", entity.getDistance()));
        builder.append("\nATK ").append(String.format("%.1f", entity.getAttackDamage()));
        builder.append("  ARM ").append(String.format("%.1f", entity.getArmorValue()));
        builder.append("  TYPE ").append(entity.isHostile() ? "HOSTILE" : "NEUTRAL");
        builder.append("\nPOS ").append(String.format("%.1f %.1f %.1f", entity.getRelativeX(), entity.getRelativeY(), entity.getRelativeZ()));
        builder.append("\nMOTION ").append(String.format("%.2f %.2f %.2f", entity.getMotionX(), entity.getMotionY(), entity.getMotionZ()));
        builder.append("\nBODY H ").append(String.format("%.2f", entity.getBoundingHeight()));
        builder.append("  EYE H ").append(String.format("%.2f", entity.getEyeHeight()));
        builder.append("\nOCC ").append(entity.isOcclusionVisible() ? "VISIBLE" : "BLOCKED");
        builder.append("  SNEAK ").append(entity.isSneaking() ? "YES" : "NO");
        builder.append("  BOW ").append(entity.isPullingBow() ? "YES" : "NO");
        if (entity.getTargetUuid() != null && !entity.getTargetUuid().isEmpty()) {
            builder.append("\nTARGET ").append(shortUuid(entity.getTargetUuid()));
        }
        if (entity.getMainHandItemId() != null && !entity.getMainHandItemId().isEmpty()) {
            builder.append("\nMAIN ").append(entity.getMainHandItemId());
        }
        return builder.toString();
    }

    private String safeText(String value, String fallback) {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private String shortUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) return "unknown";
        return uuid.length() <= 8 ? uuid : uuid.substring(0, 8);
    }

    private boolean isCrosshairHoldingFocusedTarget(String focusedUuid) {
        if (focusedUuid == null || focusedUuid.isEmpty() || environmentProvider == null) return false;
        String crosshairTarget = environmentProvider.getCrosshairTargetKey();
        return focusedUuid.equals(crosshairTarget);
    }

    private void transitionFocusBackToScanning() {
        if (!scanningCardsEnabled) {
            beginManualFocusDisappear();
            return;
        } else {
            stateMachine.transitionToScanning();
        }
        manualFocusTarget = null;
        scanningTimer = MrConstants.SCANNING_WARMUP;
        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
        aimWarmupTimer = 0.0f;
        gazeTimer = 0.0f;
        lastGazeUuid = null;
    }

    private void beginManualFocusDisappear() {
        if (manualFocusTarget == null) return;
        TrackedCard tracked = activeCards.get(manualFocusTarget.getUuid());
        if (tracked != null) {
            tracked.animation.triggerDisappear();
            tracked.suppressingForLimit = false;
        }
        manualFocusTarget = null;
        stateMachine.transitionToSilent();
        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
        aimWarmupTimer = 0.0f;
        gazeTimer = 0.0f;
        lastGazeUuid = null;
    }

    private void transitionFocusBackToScanningForGazeSwitch(String nextUuid) {
        manualFocusTarget = null;
        stateMachine.transitionToScanning();
        scanningTimer = MrConstants.SCANNING_WARMUP;
        focusExitCountdown = MrConstants.FOCUS_EXIT_COUNTDOWN_SECONDS;
        aimWarmupTimer = 0.0f;
        gazeTimer = 0.0f;
        lastGazeUuid = nextUuid;
    }

    private void updateGazeTimers(String crosshairUuid, float deltaTime) {
        if (crosshairUuid == null || crosshairUuid.isEmpty()) {
            decayGazeTimers(deltaTime);
            return;
        }
        if (crosshairUuid.equals(lastGazeUuid)) {
            aimWarmupTimer = Math.min(MrConstants.FOCUS_AIM_WARMUP_SECONDS, aimWarmupTimer + deltaTime);
            if (aimWarmupTimer >= MrConstants.FOCUS_AIM_WARMUP_SECONDS) {
                gazeTimer += deltaTime;
            }
        } else if (gazeTimer > 0.0f) {
            decayGazeTimers(deltaTime);
        } else {
            lastGazeUuid = crosshairUuid;
            aimWarmupTimer = 0.0f;
            gazeTimer = 0.0f;
        }
    }

    private void decayGazeTimers(float deltaTime) {
        if (gazeTimer > 0.0f) {
            gazeTimer = Math.max(0.0f, gazeTimer - deltaTime);
            if (gazeTimer > 0.0f) return;
        }
        resetGazeTimers();
    }

    private void resetGazeTimers() {
        aimWarmupTimer = 0.0f;
        gazeTimer = 0.0f;
        lastGazeUuid = null;
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

    private float computeCardHeight(float baseCardH, float boardScale, float contentScale, float cardW, boolean isFocused, String focusedDetailText, float visibleChars) {
        float baseHeight = baseCardH * boardScale;
        if (!isFocused || focusedDetailText == null || focusedDetailText.isEmpty() || visibleChars <= 0.0f) return baseHeight;
        int charCount = Math.max(0, Math.min((int) Math.ceil(visibleChars), focusedDetailText.length()));
        String visibleText = focusedDetailText.substring(0, charCount);
        int lines = estimateWrappedLineCount(visibleText, cardW, contentScale);
        float detailTop = (MrConstants.CONTENT_PADDING_Y + MrConstants.FONT_LINE_HEIGHT + 2.0f + MrConstants.CONTENT_BAR_SPACING + MrConstants.FONT_LINE_HEIGHT + 8.0f) * contentScale;
        float bottomPadding = (MrConstants.CONTENT_PADDING_Y + 6.0f) * contentScale;
        float requiredHeight = detailTop + lines * MrConstants.FONT_LINE_HEIGHT * contentScale + bottomPadding;
        return Math.max(baseHeight, requiredHeight);
    }

    private int estimateWrappedLineCount(String text, float cardW, float contentScale) {
        float safeScale = Math.max(0.1f, contentScale);
        float availableWidth = Math.max(1.0f, (cardW - MrConstants.CONTENT_PADDING_X * safeScale * 2.0f) / safeScale);
        float averageCharWidth = 6.0f;
        int maxCharsPerLine = Math.max(1, (int) (availableWidth / averageCharWidth));
        int lines = 0;
        String[] explicitLines = text.split("\n", -1);
        for (String line : explicitLines) {
            lines += Math.max(1, (line.length() + maxCharsPerLine - 1) / maxCharsPerLine);
        }
        return Math.max(1, lines);
    }

    private TargetGeometry computeTargetGeometry(float anchorX, float anchorY, float scale, float cardW, float cardH, int screenW, int screenH, TrackedCard tracked) {
        float segmentLength = getSegmentLength() * scale;
        float defaultQuadrantX = anchorX < screenW * 0.5f ? 1.0f : -1.0f;
        float defaultQuadrantY = anchorY < screenH * 0.5f ? 1.0f : -1.0f;
        if (tracked != null) {
            if (!tracked.hasLockedQuadrant) {
                chooseInitialQuadrant(anchorX, anchorY, segmentLength, cardW, cardH, screenW, screenH, tracked);
            } else {
                float projectedX = anchorX + tracked.lockedQuadrantX * segmentLength * 2.0f;
                float projectedY = anchorY + tracked.lockedQuadrantY * segmentLength * 1.25f;
                boolean tooFarOutside = projectedX < -cardW * 1.1f
                        || projectedX > screenW + cardW * 0.1f
                        || projectedY < -cardH * 1.1f
                        || projectedY > screenH + cardH * 0.1f;
                if (tooFarOutside) {
                    tracked.lockedQuadrantX = defaultQuadrantX;
                    tracked.lockedQuadrantY = defaultQuadrantY;
                }
            }
            defaultQuadrantX = tracked.lockedQuadrantX;
            defaultQuadrantY = tracked.lockedQuadrantY;
        }

        float targetCenterX = anchorX + defaultQuadrantX * (segmentLength * 2.0f + cardW * 0.5f);
        float targetCenterY = anchorY + defaultQuadrantY * (segmentLength * 1.25f + cardH * 0.35f);
        float cardX = targetCenterX - cardW * 0.5f;
        float cardY = targetCenterY - cardH * 0.5f;
        boolean anchorNearScreen = anchorX >= -cardW && anchorX <= screenW + cardW && anchorY >= -cardH && anchorY <= screenH + cardH;
        if (anchorNearScreen) {
            cardX = Math.max(4.0f, Math.min(screenW - cardW - 4.0f, cardX));
            cardY = Math.max(4.0f, Math.min(screenH - cardH - 4.0f, cardY));
        }

        float leftDistance = Math.abs(anchorX - cardX);
        float rightDistance = Math.abs(anchorX - (cardX + cardW));
        float topDistance = Math.abs(anchorY - cardY);
        float bottomDistance = Math.abs(anchorY - (cardY + cardH));
        int edge = 0;
        float best = topDistance;
        if (bottomDistance < best) {
            best = bottomDistance;
            edge = 1;
        }
        if (leftDistance < best) {
            best = leftDistance;
            edge = 2;
        }
        if (rightDistance < best) {
            edge = 3;
        }

        float connectorX;
        float connectorY;
        float ratio;
        if (edge == 0 || edge == 1) {
            connectorX = Math.max(cardX + cardW * MrConstants.CONNECTOR_EDGE_MIN_RATIO, Math.min(cardX + cardW * MrConstants.CONNECTOR_EDGE_MAX_RATIO, anchorX));
            ratio = (connectorX - cardX) / Math.max(1.0f, cardW);
            connectorY = edge == 0 ? cardY : cardY + cardH;
        } else {
            connectorY = Math.max(cardY + cardH * MrConstants.CONNECTOR_EDGE_MIN_RATIO, Math.min(cardY + cardH * MrConstants.CONNECTOR_EDGE_MAX_RATIO, anchorY));
            ratio = (connectorY - cardY) / Math.max(1.0f, cardH);
            connectorX = edge == 2 ? cardX : cardX + cardW;
        }
        float directionX = connectorX > anchorX ? 1.0f : connectorX < anchorX ? -1.0f : 0.0f;
        float directionY = connectorY > anchorY ? 1.0f : connectorY < anchorY ? -1.0f : 0.0f;
        if (tracked != null) {
            tracked.lastLayoutDirectionX = directionX;
            tracked.lastLayoutDirectionY = directionY;
            tracked.hasLastLayoutDirection = true;
        }
        return new TargetGeometry(cardX, cardY, directionX, directionY, edge, edge == 0, ratio);
    }

    private void chooseInitialQuadrant(float anchorX, float anchorY, float segmentLength, float cardW, float cardH, int screenW, int screenH, TrackedCard tracked) {
        float preferredX = anchorX < screenW * 0.5f ? 1.0f : -1.0f;
        float preferredY = anchorY < screenH * 0.5f ? 1.0f : -1.0f;
        float[][] quadrants = new float[][]{
                {preferredX, preferredY},
                {-preferredX, preferredY},
                {preferredX, -preferredY},
                {-preferredX, -preferredY}
        };
        float bestScore = Float.MAX_VALUE;
        float bestX = preferredX;
        float bestY = preferredY;
        for (float[] quadrant : quadrants) {
            float cardX = computeCandidateCardX(anchorX, quadrant[0], segmentLength, cardW, screenW);
            float cardY = computeCandidateCardY(anchorY, quadrant[1], segmentLength, cardH, screenH);
            float score = computeInitialQuadrantScore(cardX, cardY, cardW, cardH, tracked.uuid, quadrant[0], quadrant[1], preferredX, preferredY, screenW, screenH);
            if (score < bestScore) {
                bestScore = score;
                bestX = quadrant[0];
                bestY = quadrant[1];
            }
        }
        tracked.lockedQuadrantX = bestX;
        tracked.lockedQuadrantY = bestY;
        tracked.hasLockedQuadrant = true;
    }

    private float computeCandidateCardX(float anchorX, float quadrantX, float segmentLength, float cardW, int screenW) {
        float targetCenterX = anchorX + quadrantX * (segmentLength * 2.0f + cardW * 0.5f);
        float cardX = targetCenterX - cardW * 0.5f;
        return Math.max(4.0f, Math.min(screenW - cardW - 4.0f, cardX));
    }

    private float computeCandidateCardY(float anchorY, float quadrantY, float segmentLength, float cardH, int screenH) {
        float targetCenterY = anchorY + quadrantY * (segmentLength * 1.25f + cardH * 0.35f);
        float cardY = targetCenterY - cardH * 0.5f;
        return Math.max(4.0f, Math.min(screenH - cardH - 4.0f, cardY));
    }

    private float computeInitialQuadrantScore(float cardX, float cardY, float cardW, float cardH, String uuid, float quadrantX, float quadrantY, float preferredX, float preferredY, int screenW, int screenH) {
        float score = 0.0f;
        for (MrCardSnapshot other : frameSnapshots) {
            if (uuid != null && uuid.equals(other.entityUuid)) continue;
            score += computeOverlapArea(cardX, cardY, cardW, cardH, other.cardX, other.cardY, other.cardWidth, other.cardHeight) * 8.0f;
        }
        for (EntityScreenRect rect : entityScreenRects) {
            if (uuid != null && uuid.equals(rect.uuid)) continue;
            score += computeOverlapArea(cardX, cardY, cardW, cardH, rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top) * rect.priorityWeight * 12.0f;
        }
        score += computeOutOfScreenPenalty(cardX, cardY, cardW, cardH, screenW, screenH);
        if (quadrantX != preferredX) score += cardW * cardH * 0.08f;
        if (quadrantY != preferredY) score += cardW * cardH * 0.06f;
        return score;
    }

    private float buildTargetAngle(float normalizedX, float normalizedY) {
        float horizontalDirection = normalizedX < 0.5f ? 1.0f : -1.0f;
        float verticalDirection = computePreferredBcVerticalDirection(normalizedY);
        float angle = (float) Math.toRadians(MrConstants.BC_REST_ANGLE_DEGREES);
        return (float) Math.atan2(verticalDirection * Math.sin(angle), horizontalDirection * Math.cos(angle));
    }

    private float computePreferredBcVerticalDirection(float normalizedY) {
        if (normalizedY < 0.2f) return 1.0f;
        if (normalizedY > 0.8f) return -1.0f;
        float middleProgress = (normalizedY - 0.2f) / 0.6f;
        return middleProgress < 2.0f / 3.0f ? -1.0f : 1.0f;
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

    private float getMinCardScale() {
        try {
            return Math.max(0.1f, Math.min(4.0f, tuningProvider.getMinCardScale()));
        } catch (Exception ignored) {
            return MrConstants.CARD_MIN_SCALE;
        }
    }

    private float getMaxCardScale() {
        try {
            return Math.max(getMinCardScale(), Math.min(4.0f, tuningProvider.getMaxCardScale()));
        } catch (Exception ignored) {
            return MrConstants.CARD_MAX_SCALE;
        }
    }

    private float getSegmentLength() {
        try {
            return Math.max(8.0f, Math.min(160.0f, tuningProvider.getSegmentLength()));
        } catch (Exception ignored) {
            return MrConstants.RIGID_SEGMENT_LENGTH;
        }
    }

    private float getCardDamping() {
        try {
            return Math.max(0.01f, Math.min(0.95f, tuningProvider.getCardDamping()));
        } catch (Exception ignored) {
            return MrConstants.DAMPING_FACTOR;
        }
    }

    private float getCardMinDamping() {
        try {
            return Math.max(0.01f, Math.min(0.95f, tuningProvider.getCardMinDamping()));
        } catch (Exception ignored) {
            return MrConstants.DAMPING_FACTOR;
        }
    }

    private float getCardMaxDamping() {
        try {
            return Math.max(getCardMinDamping(), Math.min(0.95f, tuningProvider.getCardMaxDamping()));
        } catch (Exception ignored) {
            return MrConstants.DAMPING_MAX_FACTOR;
        }
    }

    private float computeEnvironmentAlphaFactor() {
        float dayAlpha = getDayAlphaFactor();
        float nightAlpha = getNightAlphaFactor();
        try {
            WorldEnvironmentData environment = environmentProvider.getWorldEnvironmentInfo();
            if (environment == null) return dayAlpha;
            long dayTime = environment.dayTimeTicks % 24000L;
            if (dayTime < 12000L) return dayAlpha;
            if (dayTime < 13000L) return lerp(dayAlpha, nightAlpha, (dayTime - 12000L) / 1000.0f);
            if (dayTime < 23000L) return nightAlpha;
            return lerp(nightAlpha, dayAlpha, (dayTime - 23000L) / 1000.0f);
        } catch (Exception ignored) {
            return dayAlpha;
        }
    }

    private float getDayAlphaFactor() {
        try {
            return Math.max(0.05f, Math.min(1.5f, tuningProvider.getDayAlphaFactor()));
        } catch (Exception ignored) {
            return MrConstants.DAY_ALPHA_FACTOR;
        }
    }

    private float getNightAlphaFactor() {
        try {
            return Math.max(0.05f, Math.min(1.5f, tuningProvider.getNightAlphaFactor()));
        } catch (Exception ignored) {
            return MrConstants.NIGHT_ALPHA_FACTOR;
        }
    }

    private float lerp(float from, float to, float t) {
        float p = Math.max(0.0f, Math.min(1.0f, t));
        return from + (to - from) * p;
    }

    private float[] computeRestJointPoint(float ax, float ay, float scale, int sw, int sh) {
        float normalizedY = ay / (float) sh;
        float segmentLength = getSegmentLength() * scale;

        if (normalizedY < 0.2f) {
            return new float[]{ax, ay + segmentLength};
        }
        if (normalizedY > 0.8f) {
            return new float[]{ax, ay - segmentLength};
        }

        float direction = ax < sw * 0.5f ? 1.0f : -1.0f;
        return new float[]{ax + direction * segmentLength, ay};
    }

    private void syncConnectorAfterCardMove(MrCardSnapshot snap) {
        if (snap.connectorEdge == 2 || snap.connectorEdge == 3) {
            snap.connectorX = snap.connectorEdge == 2 ? snap.cardX : snap.cardX + snap.cardWidth;
            snap.connectorY = snap.cardY + snap.cardHeight * snap.connectorEdgeRatio;
        } else {
            snap.connectorX = snap.cardX + snap.cardWidth * snap.connectorEdgeRatio;
            snap.connectorY = snap.connectorEdge == 0 ? snap.cardY : snap.cardY + snap.cardHeight;
        }
        snap.orthogonalHorizontalFirst = Math.abs(snap.connectorX - snap.anchorX) >= Math.abs(snap.connectorY - snap.anchorY);
    }

    private void precomputeVisuals(MrCardSnapshot snap) {
        float healthRatio = snap.maxHealth > 0.0f
                ? Math.max(0.0f, Math.min(1.0f, snap.health / snap.maxHealth))
                : 0.0f;

        int baseColor = snap.isHostile ? MrConstants.COLOR_HOSTILE : MrConstants.COLOR_NEUTRAL;
        int accentR = (baseColor >> 16) & 0xFF;
        int accentG = (baseColor >> 8) & 0xFF;
        int accentB = baseColor & 0xFF;

        float contentScale = Math.max(0.1f, snap.contentScale > 0.0f ? snap.contentScale : snap.scale);
        int contentAlphaInt = (int) (Math.max(0.0f, Math.min(1.0f, snap.alpha * snap.environmentAlphaFactor)) * 255.0f) & 0xFF;
        float barFullWidth = Math.max(0.0f, snap.cardWidth - MrConstants.CONTENT_BAR_MARGIN * contentScale);
        int healthColor;
        if (healthRatio > 0.6f) {
            healthColor = (contentAlphaInt << 24) | 0x33DD66;
        } else if (healthRatio > 0.3f) {
            healthColor = (contentAlphaInt << 24) | 0xFFCC33;
        } else {
            healthColor = (contentAlphaInt << 24) | 0xFF3333;
        }

        snap.accentColor = baseColor;
        snap.accentR = accentR;
        snap.accentG = accentG;
        snap.accentB = accentB;
        snap.textAlphaColor = (contentAlphaInt << 24) | 0xFFFFFF;
        snap.accentTextColor = (contentAlphaInt << 24) | (accentR << 16) | (accentG << 8) | accentB;
        snap.healthBarBgColor = (contentAlphaInt << 24) | 0x333333;
        snap.healthBarColor = healthColor;
        snap.healthBarFullWidth = barFullWidth;
        snap.healthBarFillWidth = barFullWidth * healthRatio;
        snap.glitchOffset = 0;
        snap.distanceText = String.format("%.1fm", snap.distance);
        if (snap.isBlockTarget) {
            snap.attackText = snap.entityId != null && !snap.entityId.isEmpty() ? "BLOCK" : null;
            snap.armorText = null;
            snap.distanceIconItemId = "minecraft:compass";
            snap.attackIconItemId = snap.entityId != null && !snap.entityId.isEmpty() ? snap.entityId : "minecraft:stone";
            snap.armorIconItemId = null;
        } else {
            snap.attackText = snap.attackDamage > 0.0f ? String.format("%.0f", snap.attackDamage) : null;
            snap.armorText = snap.armorValue > 0.0f ? String.format("%.0f", snap.armorValue) : null;
            snap.distanceIconItemId = "minecraft:compass";
            snap.attackIconItemId = snap.attackText != null ? "minecraft:iron_sword" : null;
            snap.armorIconItemId = snap.armorText != null ? "minecraft:iron_chestplate" : null;
        }
        snap.contentStartX = snap.cardWidth > 0.0f ? MrConstants.CONTENT_PADDING_X * contentScale : 0.0f;
        snap.contentStartY = snap.cardHeight > 0.0f ? MrConstants.CONTENT_PADDING_Y * contentScale : 0.0f;
        float iconSize = MrConstants.STATS_ICON_SIZE * contentScale;
        float iconTextGap = Math.max(1.0f, MrConstants.STATS_ICON_TEXT_GAP * 0.45f * contentScale);
        float groupGap = Math.max(1.0f, MrConstants.STATS_GROUP_GAP * 0.2f * contentScale);
        float statTextWidth = 5.0f * contentScale;
        float fontLineHeight = MrConstants.FONT_LINE_HEIGHT * contentScale;
        snap.nameIconX = snap.contentStartX;
        snap.nameIconY = snap.contentStartY - 2.0f * contentScale;
        snap.nameTextX = snap.contentStartX;
        snap.nameTextY = snap.contentStartY;
        snap.contentNameEndY = snap.contentStartY + fontLineHeight + 2.0f * contentScale;
        snap.contentBarEndY = snap.contentNameEndY + MrConstants.CONTENT_BAR_SPACING * contentScale;
        snap.contentStatsY = snap.contentBarEndY + 2.0f * contentScale;

        float cursorX = snap.contentStartX;
        float iconY = snap.contentStatsY - 3.0f * contentScale;
        snap.distanceIconX = cursorX;
        snap.distanceIconY = iconY;
        snap.distanceTextX = snap.distanceIconX + iconSize + iconTextGap;
        cursorX = snap.distanceTextX + snap.distanceText.length() * statTextWidth + groupGap;

        if (snap.attackText != null) {
            snap.attackIconX = cursorX;
            snap.attackIconY = iconY;
            snap.atkTextX = snap.attackIconX + iconSize + iconTextGap;
            cursorX = snap.atkTextX + snap.attackText.length() * statTextWidth + groupGap;
        } else {
            snap.attackIconX = 0.0f;
            snap.attackIconY = iconY;
            snap.atkTextX = 0.0f;
        }

        if (snap.armorText != null) {
            snap.armorIconX = cursorX;
            snap.armorIconY = iconY;
            snap.defTextX = snap.armorIconX + iconSize + iconTextGap;
        } else {
            snap.armorIconX = 0.0f;
            snap.armorIconY = iconY;
            snap.defTextX = 0.0f;
        }
    }
}
