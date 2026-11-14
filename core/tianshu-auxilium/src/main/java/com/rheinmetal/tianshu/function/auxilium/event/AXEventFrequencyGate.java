package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Corrects the tier of a kind that keeps happening.
 *
 * <p>A tier <em>is</em> a frequency, so this is where frequency is judged — not in the handling class. Two thresholds,
 * both configurable, move the tier towards "this happens all the time":
 *
 * <pre>
 * N &gt;= rareToNormalThreshold      RARE   -&gt; NORMAL
 * N &gt;= normalToFrequentThreshold  NORMAL -&gt; FREQUENT
 * FREQUENT is already the last tier
 * </pre>
 *
 * <p>The two steps apply in order, so a rare kind that goes on long enough walks the whole way: at the sixth
 * occurrence it is normal after the first step and frequent after the second. The correction is bounded above by the
 * declared tier — a frequent kind is never lifted back to normal by counting, however quiet it has been.
 *
 * <p>What the corrected tier <em>means</em> is not decided here: {@link AXEventPolicy} turns (tier, list) into a
 * handling class and holds no threshold at all. Counting is per kind, because "does this kind keep happening" is a
 * property of the kind alone.
 */
final class AXEventFrequencyGate {
    private final AXEventSettings settings;
    private final Map<String, ArrayDeque<Long>> occurrencesByKind = new HashMap<>();

    AXEventFrequencyGate(AXEventSettings settings) {
        this.settings = settings == null ? AXEventSettings.DEFAULT : settings;
    }

    /**
     * Records this occurrence and returns the tier it should be treated as.
     *
     * @param declaredTier the tier the descriptor or the table says, before frequency is taken into account
     * @return the declared tier, or one or two steps down the frequency ladder when the kind keeps happening
     */
    AXEventTier correctedTier(String kind, AXEventTier declaredTier, long nowMillis) {
        AXEventTier declared = declaredTier == null ? AXEventTier.FREQUENT : declaredTier;
        if (declared == AXEventTier.FREQUENT) {
            // Already the most frequent tier: nothing to correct, and no reason to keep a count.
            return AXEventTier.FREQUENT;
        }
        int count = record(kind, nowMillis);
        AXEventTier corrected = declared;
        if (corrected == AXEventTier.RARE && count >= Math.max(1, settings.rareToNormalThreshold())) {
            corrected = AXEventTier.NORMAL;
        }
        if (corrected == AXEventTier.NORMAL && count >= Math.max(1, settings.normalToFrequentThreshold())) {
            corrected = AXEventTier.FREQUENT;
        }
        return corrected;
    }

    /** Number of occurrences of {@code kind} inside the window as of {@code nowMillis}. */
    int count(String kind, long nowMillis) {
        ArrayDeque<Long> timestamps = occurrencesByKind.get(normalize(kind));
        if (timestamps == null) {
            return 0;
        }
        prune(timestamps, nowMillis);
        return timestamps.size();
    }

    private int record(String kind, long nowMillis) {
        ArrayDeque<Long> timestamps = occurrencesByKind.computeIfAbsent(normalize(kind), ignored -> new ArrayDeque<>());
        prune(timestamps, nowMillis);
        timestamps.addLast(nowMillis);
        return timestamps.size();
    }

    private void prune(ArrayDeque<Long> timestamps, long nowMillis) {
        long cutoff = nowMillis - Math.max(1L, settings.frequencyWindowMillis());
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.pollFirst();
        }
    }

    private static String normalize(String kind) {
        return kind == null ? "" : kind.trim();
    }
}
