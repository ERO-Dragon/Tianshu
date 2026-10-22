package com.rheinmetal.tianshu.function.auxilium.producer;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a stream of player readings into event signals by detecting threshold crossings.
 *
 * <p>State values such as health are read every sample but are <em>not</em> events: only the moment a
 * value crosses a boundary, changes in the informative direction, or the player's dimension or
 * biome changes produces a signal. That keeps a per-frame reading cheap and makes the produced stream
 * independent of the sampling rate.
 *
 * <p>The first sample after a reset only establishes the baseline, so joining a world never reports a
 * dimension change or a full-health "recovery".
 *
 * <p>Not thread safe: the platform hook drives it from the client thread.
 */
public final class AXGameEventProbe {
    private static final int UNKNOWN = -1;

    private final AXGameEventThresholds thresholds;
    private int lastHealthPoints = UNKNOWN;
    private int lastFoodLevel = UNKNOWN;
    private String lastDimensionId = "";
    private String lastBiomeId = "";
    private boolean lastDying;

    public AXGameEventProbe() {
        this(AXGameEventThresholds.DEFAULT);
    }

    public AXGameEventProbe(AXGameEventThresholds thresholds) {
        this.thresholds = thresholds == null ? AXGameEventThresholds.DEFAULT : thresholds;
    }

    /**
     * Feeds one reading and returns the signals it produced.
     *
     * @return an immutable list, empty when the sample was a baseline or nothing crossed
     */
    public List<AXGameEventSignal> sample(AXGameEventSample sample) {
        if (sample == null) {
            return List.of();
        }
        if (lastHealthPoints == UNKNOWN) {
            baseline(sample);
            return List.of();
        }
        List<AXGameEventSignal> signals = null;
        if (!lastDying && sample.dying()) {
            baseline(sample);
            return List.of(new AXGameEventSignal(AXGameEventKinds.DEATH, 0));
        }
        if (lastDying && !sample.dying()) {
            baseline(sample);
            return List.of(new AXGameEventSignal(AXGameEventKinds.RESPAWN, sample.healthPoints()));
        }
        if (sample.dying()) {
            // While dying the health and hunger bars only feed the death transition, so a final
            // "lost the rest of your health" event is never produced.
            baseline(sample);
            return List.of();
        }
        if (sample.healthPoints() < lastHealthPoints) {
            signals = add(signals, AXGameEventKinds.HEALTH_CHANGE, lastHealthPoints - sample.healthPoints());
        }
        int healthThreshold = thresholds.healthCriticalPoints();
        if (lastHealthPoints > healthThreshold && sample.healthPoints() <= healthThreshold) {
            signals = add(signals, AXGameEventKinds.HEALTH_CRITICAL, sample.healthPoints());
        }
        if (sample.foodLevel() < lastFoodLevel) {
            signals = add(signals, AXGameEventKinds.HUNGER_CHANGE, lastFoodLevel - sample.foodLevel());
        }
        int hungerThreshold = thresholds.hungerCriticalLevel();
        if (lastFoodLevel > hungerThreshold && sample.foodLevel() <= hungerThreshold) {
            signals = add(signals, AXGameEventKinds.HUNGER_CRITICAL, sample.foodLevel());
        }
        if (!lastDimensionId.isEmpty() && !lastDimensionId.equals(sample.dimensionId())) {
            signals = add(signals, AXGameEventKinds.DIMENSION_ENTER, 0);
            // A dimension jump also changes the biome; reporting both would say the same thing twice.
            lastBiomeId = sample.biomeId();
        } else if (!lastBiomeId.isEmpty() && !lastBiomeId.equals(sample.biomeId())) {
            signals = add(signals, AXGameEventKinds.BIOME_CHANGE, 0);
        }
        baseline(sample);
        return signals == null ? List.of() : List.copyOf(signals);
    }

    /** Drops the baseline; the next sample starts a new session and reports nothing. */
    public void reset() {
        lastHealthPoints = UNKNOWN;
        lastFoodLevel = UNKNOWN;
        lastDimensionId = "";
        lastBiomeId = "";
        lastDying = false;
    }

    private void baseline(AXGameEventSample sample) {
        lastHealthPoints = sample.healthPoints();
        lastFoodLevel = sample.foodLevel();
        lastDying = sample.dying();
        if (!sample.dimensionId().isEmpty()) {
            lastDimensionId = sample.dimensionId();
        }
        if (!sample.biomeId().isEmpty()) {
            lastBiomeId = sample.biomeId();
        }
    }

    private List<AXGameEventSignal> add(List<AXGameEventSignal> signals, String kind, int value) {
        List<AXGameEventSignal> target = signals == null ? new ArrayList<>(2) : signals;
        target.add(new AXGameEventSignal(kind, value));
        return target;
    }
}
