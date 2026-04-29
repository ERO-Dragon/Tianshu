package com.rheinmetal.tianshu.function.MR;

import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.provider.IRenderContextProvider;
import com.rheinmetal.tianshu.snapshot.MatrixSnapshot;
import com.rheinmetal.tianshu.snapshot.NearbyEntityData;
import com.rheinmetal.tianshu.snapshot.PositionData;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MrEngine {

    private final MrStateMachine stateMachine = new MrStateMachine();
    private final ConcurrentLinkedQueue<MrCardSnapshot> outputQueue = new ConcurrentLinkedQueue<>();

    private final IEnvironmentAwarenessProvider environmentProvider;
    private final IRenderContextProvider renderContextProvider;

    private final Map<String, TrackedCard> activeCards = new LinkedHashMap<>();
    private float staggerClock = 0.0f;
    private int staggerCountThisSecond = 0;
    private float staggerSecondTimer = 0.0f;

    private volatile boolean running = false;

    private static final class TrackedCard {
        final String uuid;
        MrAnimationController animation;
        MrWhipLayout layout;
        MrCardSnapshot lastSnapshot;
        float losGraceTimer = 0.0f;
        boolean wasLineOfSight = true;

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
    }

    public void stop() {
        stateMachine.transitionToSilent();
        running = false;
        activeCards.clear();
        outputQueue.clear();
    }

    public void tick(PositionData playerPos) {
        if (!stateMachine.isActive() || playerPos == null) {
            outputQueue.clear();
            return;
        }

        float deltaTime = MrConstants.TICK_DURATION;
        staggerSecondTimer += deltaTime;
        if (staggerSecondTimer >= 1.0f) {
            staggerSecondTimer -= 1.0f;
            staggerCountThisSecond = 0;
        }

        List<NearbyEntityData> hostiles = environmentProvider.getNearbyHostiles(MrConstants.MR_RANGE);

        hostiles.sort(Comparator.comparingDouble(NearbyEntityData::getDistance));

        if (hostiles.size() > MrConstants.MAX_CARDS) {
            hostiles = hostiles.subList(0, MrConstants.MAX_CARDS);
        }

        MatrixSnapshot projMatrix = renderContextProvider.getProjectionMatrix();
        MatrixSnapshot mvMatrix = renderContextProvider.getModelViewMatrix();
        int screenW = renderContextProvider.getScreenWidth();
        int screenH = renderContextProvider.getScreenHeight();

        if (projMatrix == null || mvMatrix == null || screenW <= 0 || screenH <= 0) return;

        float[] projData = projMatrix.getData();
        float[] mvData = mvMatrix.getData();

        Set<String> currentUuids = new HashSet<>();
        List<MrCardSnapshot> frameSnapshots = new ArrayList<>();

        for (NearbyEntityData entity : hostiles) {
            String uuid = entity.getUuid();
            currentUuids.add(uuid);

            double entityWorldX = playerPos.getX() + entity.getRelativeX();
            double entityWorldY = playerPos.getY() + entity.getRelativeY() + 1.8 + 0.2;
            double entityWorldZ = playerPos.getZ() + entity.getRelativeZ();

            float[] screenPos = MrProjector.project(
                    entityWorldX, entityWorldY, entityWorldZ,
                    playerPos.getX(), playerPos.getY(), playerPos.getZ(),
                    playerPos.getYaw(), playerPos.getPitch(),
                    projData, mvData,
                    screenW, screenH
            );

            if (screenPos == null) continue;

            float anchorX = screenPos[0];
            float anchorY = screenPos[1];

            if (!MrProjector.isInHardBounds(anchorX, anchorY, screenW, screenH)) continue;

            TrackedCard tracked = activeCards.get(uuid);
            if (tracked == null) {
                tracked = new TrackedCard(uuid);
                float staggerDelay = 0.0f;
                if (staggerCountThisSecond < MrConstants.STAGGER_MAX_PER_SECOND) {
                    staggerDelay = staggerCountThisSecond * MrConstants.STAGGER_DELAY;
                    staggerCountThisSecond++;
                }
                tracked.animation.setStaggerDelay(staggerDelay);
                activeCards.put(uuid, tracked);
            }

            if (!entity.isLineOfSight() && tracked.wasLineOfSight) {
                tracked.losGraceTimer = 0.0f;
                tracked.wasLineOfSight = false;
            } else if (entity.isLineOfSight()) {
                tracked.wasLineOfSight = true;
                tracked.losGraceTimer = 0.0f;
            }

            if (!entity.isLineOfSight()) {
                tracked.losGraceTimer += deltaTime;
                if (tracked.losGraceTimer > MrConstants.LOS_FOLLOW_GRACE_PERIOD) {
                    tracked.animation.triggerDisappear();
                }
            } else {
                if (tracked.animation.isFullyDead()) {
                    tracked.animation = new MrAnimationController();
                    tracked.layout = new MrWhipLayout();
                }
            }

            tracked.animation.tick(deltaTime);

            if (tracked.animation.isFullyDead()) {
                activeCards.remove(uuid);
                continue;
            }

            float dist = (float) entity.getDistance();
            float scale = (float) (MrConstants.BASE_DISTANCE / Math.max(dist, 1.0));
            scale = Math.max(0.4f, Math.min(1.5f, scale));

            float cardW = MrConstants.CARD_BASE_WIDTH * scale;
            float cardH = MrConstants.CARD_BASE_HEIGHT * scale;

            float targetCardX = anchorX - cardW * 0.5f;
            float targetCardY = anchorY;

            boolean isFocused = stateMachine.isFocusing()
                    && uuid.equals(stateMachine.getFocusedEntityUuid());

            if (isFocused) {
                scale = MrConstants.FOCUS_SCALE;
                cardW = MrConstants.CARD_BASE_WIDTH * scale;
                cardH = MrConstants.CARD_BASE_HEIGHT * scale;
                targetCardX = screenW * 0.5f - cardW * 0.5f;
                targetCardY = screenH * 0.15f;
            }

            MrWhipLayout.LayoutResult layoutResult = tracked.layout.compute(
                    anchorX, anchorY,
                    targetCardX, targetCardY,
                    scale, cardW, cardH,
                    screenW, screenH, deltaTime
            );

            if (layoutResult.whipBroken) {
                tracked.layout.reset();
                tracked.animation = new MrAnimationController();
                tracked.animation.setStaggerDelay(0.0f);
                continue;
            }

            float distanceAlpha = 1.0f - (dist / (float) MrConstants.MR_RANGE) * MrConstants.DISTANCE_ALPHA_FACTOR;
            float animAlpha = tracked.animation.getAnimationAlpha();
            float alpha = distanceAlpha * animAlpha * layoutResult.softBoundAlpha;

            boolean entityAlive = entity.getHealth() > 0.0f;
            boolean isGrayscale = !entityAlive || tracked.animation.isDeathTriggered();

            if (!entityAlive && !tracked.animation.isDeathTriggered()) {
                tracked.animation.triggerDeath();
            }

            MrCardSnapshot snap = new MrCardSnapshot();
            snap.anchorX = anchorX;
            snap.anchorY = anchorY;
            snap.jointX = layoutResult.jointX;
            snap.jointY = layoutResult.jointY;
            snap.cardX = layoutResult.cardX;
            snap.cardY = layoutResult.cardY;
            snap.cardWidth = cardW;
            snap.cardHeight = cardH;
            snap.scale = scale;
            snap.alpha = Math.max(0.0f, Math.min(1.0f, alpha));
            snap.distanceFadeAlpha = distanceAlpha;
            snap.appearProgress = tracked.animation.getAppearProgress();
            snap.disappearProgress = tracked.animation.getDisappearProgress();
            snap.isAlive = entityAlive;
            snap.isHostile = entity.isHostile();
            snap.isLineOfSight = entity.isLineOfSight();
            snap.isFocused = isFocused;
            snap.isBackground = false;
            snap.shouldKill = layoutResult.whipBroken;
            snap.isGrayscale = isGrayscale;
            snap.displayName = entity.getDisplayName();
            snap.health = entity.getHealth();
            snap.maxHealth = entity.getMaxHealth();
            snap.distance = dist;
            snap.attackDamage = entity.getAttackDamage();
            snap.armorValue = entity.getArmorValue();
            snap.mainHandItemId = entity.getMainHandItemId();
            snap.accentColor = entity.isHostile() ? MrConstants.COLOR_HOSTILE : MrConstants.COLOR_NEUTRAL;
            snap.entityUuid = uuid;

            tracked.lastSnapshot = snap;
            frameSnapshots.add(snap);
        }

        if (stateMachine.isFocusing()) {
            String focusedUuid = stateMachine.getFocusedEntityUuid();
            for (MrCardSnapshot snap : frameSnapshots) {
                if (!snap.entityUuid.equals(focusedUuid)) {
                    snap.isBackground = true;
                    snap.scale *= MrConstants.BACKGROUND_SCALE;
                    snap.cardWidth *= MrConstants.BACKGROUND_SCALE;
                    snap.cardHeight *= MrConstants.BACKGROUND_SCALE;
                    snap.alpha *= 0.4f;
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
                }
            }
        }

        MrWhipLayout.resolveCollisions(frameSnapshots);

        outputQueue.clear();
        for (MrCardSnapshot snap : frameSnapshots) {
            outputQueue.offer(snap);
        }
    }
}
