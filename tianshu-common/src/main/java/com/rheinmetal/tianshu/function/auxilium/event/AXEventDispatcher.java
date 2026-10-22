package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a submitted event into the action the caller has to perform.
 *
 * <p>This is the single landing point of the operation matrix: the effective tier selects the row,
 * the effective blacklist flag selects the column, and the speech gate turns "speak now" into
 * "speak later" while a cooldown is still open.
 *
 * <pre>
 *            not blacklisted                          blacklisted
 *   high     append and speak now                      append only
 *   mid      append and buffer for idle speech         append only
 *   low      append only, never speak                  drop, not even a history line
 * </pre>
 *
 * <p>The class never touches wording: it decides, and the sentence stays the one the producer put on
 * {@link AXEventDescriptor#text()}. Nothing here inspects {@link AXEventDescriptor#sourceId()}; the
 * source only selects a cooldown slot and an aggregation window, so a module AX has never heard of
 * follows exactly the same path as the built-in producer.
 *
 * <p>The class is not thread safe by design: events are submitted from the game loop. It owns the
 * speech gate state and the aggregation windows, so the caller only has to wire one object.
 */
public final class AXEventDispatcher {
    private final AXEventTierTable tierTable;
    private final AXEventSettings settings;
    private final AXEventAggregator aggregator;
    private final Map<String, Long> lastSpeechAtBySlot = new HashMap<>();
    private long lastSpeechAtMillis = Long.MIN_VALUE;

    public AXEventDispatcher() {
        this(AXEventTierTable.builtin(), AXEventSettings.DEFAULT);
    }

    public AXEventDispatcher(AXEventTierTable tierTable, AXEventSettings settings) {
        this.tierTable = tierTable == null ? AXEventTierTable.builtin() : tierTable;
        this.settings = settings == null ? AXEventSettings.DEFAULT : settings;
        this.aggregator = new AXEventAggregator(this.settings);
    }

    /**
     * Resolves the tier, the blacklist flag and the matrix outcome of one event.
     *
     * <p>A declared tier always wins over the table; otherwise the table decides, and an unknown kind
     * falls back to {@link AXEventTier#LOW} so a module AX does not know can never interrupt the
     * player.
     *
     * @param nowMillis monotonic wall clock of the calling tick; only gaps and windows use it, and it
     *                  never reaches the appended line
     */
    public AXEventDecision decide(AXEventDescriptor descriptor, long nowMillis) {
        AXEventTier tier = resolveTier(descriptor);
        boolean blacklisted = resolveBlacklisted(descriptor);

        if (tier == AXEventTier.LOW && blacklisted) {
            return new AXEventDecision(AXEventAction.DROP, tier, true, false);
        }
        if (tier == AXEventTier.HIGH && !blacklisted) {
            // High tier is appended immediately either way; the gate only moves the sentence from
            // "speak now" to "queue it", it never holds the history entry back.
            String slot = speechSlot(descriptor);
            if (maySpeakNow(slot, nowMillis)) {
                recordSpeech(slot, nowMillis);
                return new AXEventDecision(AXEventAction.APPEND_AND_SPEAK, tier, false, false);
            }
            return new AXEventDecision(AXEventAction.APPEND_AND_BUFFER, tier, false, false);
        }

        AXEventAction action = tier == AXEventTier.MID && !blacklisted
                ? AXEventAction.APPEND_AND_BUFFER
                : AXEventAction.APPEND_ONLY;
        boolean folded = aggregator.offer(descriptor, tier, nowMillis);
        return new AXEventDecision(action, tier, blacklisted, folded);
    }

    /** Appends closed by an expired aggregation window; append them in order before the next turn. */
    public List<AXEventAppend> drainDueAppends(long nowMillis) {
        return aggregator.drainDue(nowMillis);
    }

    /** Appends closed by force; call this before assembling a turn so no folded event is lost. */
    public List<AXEventAppend> flushAppends() {
        return aggregator.drainAll();
    }

    private AXEventTier resolveTier(AXEventDescriptor descriptor) {
        if (descriptor.tier() != null) {
            return descriptor.tier();
        }
        return tierTable.tierOf(descriptor.kind()).orElse(AXEventTier.LOW);
    }

    private boolean resolveBlacklisted(AXEventDescriptor descriptor) {
        return descriptor.suppress() || tierTable.blacklisted(descriptor.kind());
    }

    /**
     * Cooldown slot of the same kind speech gap.
     *
     * <p>The slot is the only thing the source takes part in besides being recorded, so two producers
     * sharing a kind do not silence each other while a single producer still cannot repeat itself.
     */
    private String speechSlot(AXEventDescriptor descriptor) {
        return descriptor.sourceId() + ":" + descriptor.kind();
    }

    private boolean maySpeakNow(String slot, long nowMillis) {
        if (lastSpeechAtMillis != Long.MIN_VALUE
                && nowMillis - lastSpeechAtMillis < settings.globalMinGapMillis()) {
            return false;
        }
        Long lastSameSlot = lastSpeechAtBySlot.get(slot);
        return lastSameSlot == null
                || nowMillis - lastSameSlot >= settings.sameKindMinGapMillis();
    }

    private void recordSpeech(String slot, long nowMillis) {
        lastSpeechAtMillis = nowMillis;
        lastSpeechAtBySlot.put(slot, nowMillis);
    }
}
