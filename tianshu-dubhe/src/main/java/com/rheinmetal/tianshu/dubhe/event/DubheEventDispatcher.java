package com.rheinmetal.tianshu.dubhe.event;

import java.util.List;
import java.util.function.Function;

/**
 * Turns a submitted event into the handling class the caller has to perform.
 *
 * <p>Three inputs decide, in this order: the effective tier, the effective blacklist flag, and how often the kind
 * has happened recently.
 *
 * <ol>
 *   <li>{@link DubheEventFrequencyGate} corrects the tier when the kind keeps happening — a tier is a frequency, so the
 *       frequency question is answered in the tier, not in the handling class.</li>
 *   <li>{@link DubheEventPolicy} maps (corrected tier, list) to a class. It is a pure function: no threshold, no clock.</li>
 *   <li>A {@link DubheEventClass#SILENT} occurrence goes through the fold windows: the history gets one line with a
 *       count instead of one line per occurrence.</li>
 * </ol>
 *
 * <p>The class never touches wording: it decides, and the sentence it stores is the one the caller already
 * rendered from the descriptor's {@link DubheEventText}. Nothing here inspects {@link DubheEventDescriptor#sourceId()};
 * a module AX has never heard of follows exactly the same path as the built-in producer.
 *
 * <p>Not thread safe by design: events are submitted from the game loop. It owns the frequency gate and the fold
 * windows, so the caller only has to wire one object.
 */
public final class DubheEventDispatcher {
    private final DubheEventTierTable tierTable;
    private final DubheEventSettings settings;
    private final DubheEventAggregator aggregator;
    private final DubheEventFrequencyGate frequencyGate;

    public DubheEventDispatcher() {
        this(DubheEventTierTable.builtin(), DubheEventSettings.DEFAULT);
    }

    public DubheEventDispatcher(DubheEventTierTable tierTable, DubheEventSettings settings) {
        this(tierTable, settings, null);
    }

    /**
     * @param renderer renders a fold template into a sentence; it is the same renderer that words a single event,
     *                 which keeps a folded line inside the language table instead of inside the producer
     */
    public DubheEventDispatcher(DubheEventTierTable tierTable, DubheEventSettings settings,
                             Function<DubheEventText, String> renderer) {
        this.tierTable = tierTable == null ? DubheEventTierTable.builtin() : tierTable;
        this.settings = settings == null ? DubheEventSettings.DEFAULT : settings;
        this.aggregator = new DubheEventAggregator(this.settings, renderer);
        this.frequencyGate = new DubheEventFrequencyGate(this.settings);
    }

    /**
     * Resolves the handling class of one occurrence.
     *
     * <p>A declared tier always wins over the table; otherwise the table decides, and an unknown kind falls back
     * to {@link DubheEventTier#FREQUENT} — the most conservative tier, so a module AX does not know can never
     * interrupt the player.
     *
     * <p>The order is fixed and each step owns one question: the frequency gate first corrects the <em>tier</em> (a tier
     * is a frequency, so repetition belongs there), then {@link DubheEventPolicy} — a pure function of tier and list —
     * names the class. A producer that marks everything as highlighted therefore cannot turn AX into a repeater: its
     * kind is simply corrected to a more frequent tier, and the list then nudges that tier up one step instead of up
     * from the original one.
     *
     * @param nowMillis monotonic wall clock of the calling tick; only windows use it, and it never reaches the
     *                  appended line
     */
    public DubheEventDecision decide(DubheEventDescriptor descriptor, String sentence, long nowMillis) {
        DubheEventTier declaredTier = resolveTier(descriptor);
        DubheEventTier tier = frequencyGate.correctedTier(descriptor.kind(), declaredTier, nowMillis);
        DubheEventList list = resolveList(descriptor);
        DubheEventClass eventClass = DubheEventPolicy.classFor(tier, list);
        boolean blacklisted = list == DubheEventList.BLACKLIST;
        if (eventClass != DubheEventClass.SILENT) {
            return new DubheEventDecision(eventClass, tier, blacklisted, false);
        }
        // Only the silent class folds: the history keeps one counted line instead of one line per occurrence, and
        // the window key includes the rendered line so two different payloads of the same kind never merge.
        boolean folded = aggregator.offer(descriptor, sentence, nowMillis);
        return new DubheEventDecision(eventClass, tier, blacklisted, folded);
    }

    /** Appends closed by an expired fold window; append them in order before the next turn. */
    public List<DubheEventAppend> drainDueAppends(long nowMillis) {
        return aggregator.drainDue(nowMillis);
    }

    /** Appends closed by force; call this before assembling a turn so no folded event is lost. */
    public List<DubheEventAppend> flushAppends() {
        return aggregator.drainAll();
    }

    /** Occurrences of {@code kind} inside the frequency window as of {@code nowMillis}; diagnostics and tests. */
    public int recentOccurrences(String kind, long nowMillis) {
        return frequencyGate.count(kind, nowMillis);
    }

    private DubheEventTier resolveTier(DubheEventDescriptor descriptor) {
        if (descriptor.tier() != null) {
            return descriptor.tier();
        }
        return tierTable.tierOf(descriptor.kind()).orElse(DubheEventTier.FREQUENT);
    }

    /**
     * Where this occurrence sits on the attention list.
     *
     * <p>The producer's per-occurrence flags come first: a {@code highlight} is a dynamic whitelist and outranks the
     * static list (the module is the one that knows whether this occurrence matters); {@code suppress} is the
     * standing "do not interrupt for this" of the producer and registers as a blacklist. Without either, the static
     * table answers.
     */
    private DubheEventList resolveList(DubheEventDescriptor descriptor) {
        if (descriptor.highlight()) {
            return DubheEventList.WHITELIST;
        }
        if (descriptor.suppress()) {
            return DubheEventList.BLACKLIST;
        }
        return tierTable.listOf(descriptor.kind());
    }
}
