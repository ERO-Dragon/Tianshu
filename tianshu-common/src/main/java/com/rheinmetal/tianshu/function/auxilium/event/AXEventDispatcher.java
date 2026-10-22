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
 * <p>The class is not thread safe by design: events are submitted from the game loop. It owns the
 * speech gate state and the aggregation windows, so the caller only has to wire one object.
 */
public final class AXEventDispatcher {
    private final AXEventTierTable tierTable;
    private final AXEventSettings settings;
    private final AXEventAggregator aggregator;
    private final Map<String, Long> lastSpeechAtByKind = new HashMap<>();
    private long lastSpeechAtMillis = Long.MIN_VALUE;

    public AXEventDispatcher() {
        this(AXEventTierTable.builtin(), AXEventSettings.DEFAULT);
    }

    public AXEventDispatcher(AXEventTierTable tierTable, AXEventSettings settings) {
        this.tierTable = tierTable == null ? AXEventTierTable.builtin() : tierTable;
        this.settings = settings == null ? AXEventSettings.DEFAULT : settings;
        this.aggregator = new AXEventAggregator(this.tierTable, this.settings);
    }

    /**
     * Resolves the tier, the blacklist flag and the matrix outcome of one event.
     *
     * <p>A declared tier always wins over the table; otherwise the table decides, and an unknown kind
     * falls back to {@link AXEventTier#LOW} so a mod AX does not know can never interrupt the player.
     *
     * @param nowMillis monotonic wall clock of the calling tick; only gaps and windows use it, and it
     *                  never reaches the rendered history
     */
    public AXEventDecision decide(AXEventDescriptor descriptor, long nowMillis) {
        String kind = descriptor.kind();
        AXEventTier tier = resolveTier(descriptor);
        boolean blacklisted = resolveBlacklisted(descriptor);

        if (tier == AXEventTier.LOW && blacklisted) {
            return new AXEventDecision(AXEventAction.DROP, tier, true, null, null, false);
        }
        if (tier == AXEventTier.HIGH && !blacklisted) {
            // High tier is appended immediately either way; the gate only moves the sentence from
            // "speak now" to "queue it", it never holds the history entry back.
            AXEventText speech = AXEventText.speech(kind, descriptor.payload().text());
            if (maySpeakNow(kind, nowMillis)) {
                recordSpeech(kind, nowMillis);
                return new AXEventDecision(AXEventAction.APPEND_AND_SPEAK, tier, false, speech,
                        descriptor.payload().text(), false);
            }
            return new AXEventDecision(AXEventAction.APPEND_AND_BUFFER, tier, false, speech,
                    descriptor.payload().text(), false);
        }

        AXEventAction action = AXEventAction.APPEND_ONLY;
        AXEventText speech = null;
        if (tier == AXEventTier.MID && !blacklisted) {
            action = AXEventAction.APPEND_AND_BUFFER;
            speech = AXEventText.speech(kind, descriptor.payload().text());
        }
        boolean folded = aggregator.offer(descriptor, tier, nowMillis);
        return new AXEventDecision(action, tier, blacklisted, speech, folded ? null : descriptor.payload().text(), folded);
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

    private boolean maySpeakNow(String kind, long nowMillis) {
        if (lastSpeechAtMillis != Long.MIN_VALUE
                && nowMillis - lastSpeechAtMillis < settings.globalMinGapMillis()) {
            return false;
        }
        Long lastSameKind = lastSpeechAtByKind.get(kind);
        return lastSameKind == null
                || nowMillis - lastSameKind >= settings.sameKindMinGapMillis();
    }

    private void recordSpeech(String kind, long nowMillis) {
        lastSpeechAtMillis = nowMillis;
        lastSpeechAtByKind.put(kind, nowMillis);
    }
}
