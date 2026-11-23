package com.rheinmetal.tianshu.dubhe.shared.event;

import java.util.List;
import java.util.function.Function;

/**
 * Turns a submitted event into the handling class the caller has to perform.
 *
 * <p>Three inputs decide, in this order: the effective frequency row, the attention state, and how often the kind has
 * happened recently.</p>
 *
 * <ol>
 *   <li>{@link DubheEventFrequencyGate} corrects the row when the kind keeps happening — frequency is answered by the
 *       row, never by the state.</li>
 *   <li>{@link DubheEventPolicy} maps (row, state) to one fixed class; it is a pure function with no threshold and
 *       no clock.</li>
 *   <li>{@link DubheEventClass#SILENT} goes through the counted fold windows; {@link DubheEventClass#FOLD} goes
 *       through the window-only fold, whose line is marked transient and which drops below its threshold.</li>
 * </ol>
 *
 * <p>The class never touches wording: it decides, and the sentence it stores is the one the caller already
 * rendered from the descriptor's {@link DubheEventText}.</p>
 *
 * <p>Not thread safe by design: events are submitted from the game loop. It owns the frequency gate and the fold
 * windows, so the caller only has to wire one object.</p>
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
     * <p>A declared tier always wins over the table; otherwise the table decides, and an unknown kind falls back to
     * {@link DubheEventTier#FREQUENT} — the most conservative row, so a module this one does not know can never
     * interrupt the player.</p>
     */
    public DubheEventDecision decide(DubheEventDescriptor descriptor, String sentence, long nowMillis) {
        DubheEventTier declaredTier = resolveTier(descriptor);
        DubheEventTier tier = frequencyGate.correctedTier(descriptor.kind(), declaredTier, nowMillis);
        DubheEventState state = resolveState(descriptor, tier);
        DubheEventClass eventClass = DubheEventPolicy.classFor(tier, state);

        boolean folded = false;
        if (eventClass == DubheEventClass.SILENT) {
            folded = aggregator.offer(descriptor, sentence, nowMillis);
        } else if (eventClass == DubheEventClass.FOLD) {
            folded = aggregator.offerTransient(descriptor, sentence, nowMillis);
        }
        return new DubheEventDecision(eventClass, tier, state, folded);
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
     * Which attention state this occurrence carries.
     *
     * <p>Per-occurrence flags come first: {@code highlight} is a dynamic emphasis and {@code suppress} a dynamic
     * weakening; neither changes the row. Otherwise the table answers for the corrected row.</p>
     */
    private DubheEventState resolveState(DubheEventDescriptor descriptor, DubheEventTier tier) {
        if (descriptor.highlight()) {
            return DubheEventState.EMPHASIS;
        }
        if (descriptor.suppress()) {
            return DubheEventState.WEAKENED;
        }
        return tierTable.stateFor(descriptor.kind(), tier);
    }
}