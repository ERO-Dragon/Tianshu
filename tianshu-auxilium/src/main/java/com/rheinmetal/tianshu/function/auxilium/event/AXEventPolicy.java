package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * The one table that turns a tier and the attention list into a handling class.
 *
 * <p>Implemented as a ladder, not as nine hand-written cells:
 *
 * <pre>
 * ladder:   IMMEDIATE &gt; DEFERRED &gt; SILENT &gt; DROP
 * baseline: RARE -&gt; DEFERRED     NORMAL -&gt; SILENT     FREQUENT -&gt; DROP
 * list:     WHITELIST = one step up     NEUTRAL = baseline     BLACKLIST = one step down
 * </pre>
 *
 * <p>Both ends clamp, which is what makes the rule total: a whitelisted rare kind saturates at
 * {@link AXEventClass#IMMEDIATE}, a blacklisted frequent kind saturates at {@link AXEventClass#DROP}. Expanding it
 * gives the matrix the design document states —
 *
 * <pre>
 *            WHITELIST   NEUTRAL    BLACKLIST
 *   RARE     IMMEDIATE   DEFERRED   SILENT
 *   NORMAL   DEFERRED    SILENT     DROP
 *   FREQUENT SILENT      DROP       DROP
 * </pre>
 *
 * <p>The list is not a tier value: it never changes <em>which</em> tier a kind has, it only nudges the handling one
 * step. That is why a blacklisted rare event is still worth saying during a lull instead of being thrown away.
 */
public final class AXEventPolicy {

    private AXEventPolicy() {
    }

    /** The class a tier gets when the kind is on no list at all. */
    public static AXEventClass baselineFor(AXEventTier tier) {
        AXEventTier effectiveTier = tier == null ? AXEventTier.FREQUENT : tier;
        return switch (effectiveTier) {
            case RARE -> AXEventClass.DEFERRED;
            case NORMAL -> AXEventClass.SILENT;
            case FREQUENT -> AXEventClass.DROP;
        };
    }

    public static AXEventClass classFor(AXEventTier tier, AXEventList list) {
        AXEventClass baseline = baselineFor(tier);
        AXEventList effectiveList = list == null ? AXEventList.NEUTRAL : list;
        return switch (effectiveList) {
            case WHITELIST -> baseline.upgraded();
            case NEUTRAL -> baseline;
            case BLACKLIST -> baseline.downgraded();
        };
    }

    /**
     * The tier a neutral producer must declare so that {@link #classFor} lands on {@code requested} — the inverse of
     * the table above, used by the event entry external modules submit to.
     *
     * <p>The inverse exists because a tier is this module's own vocabulary: it answers "how often does this happen",
     * a question only the built-in capture layer can answer. A producer outside states the behaviour it wants, and
     * this method translates that into the one declaration the table needs.</p>
     *
     * <p>The translation is not unique in general ({@code DEFERRED} is reachable three ways); it picks the plainest
     * row so the frequency gate and the attention list keep room to move the result down later.</p>
     *
     * @throws IllegalArgumentException for {@link AXEventClass#DROP}, which has no inverse: a producer that wants
     *         nothing to happen simply does not submit
     */
    public static AXEventTier tierFor(AXEventClass requested) {
        if (requested == null || requested == AXEventClass.DROP) {
            throw new IllegalArgumentException("DROP cannot be requested: do not submit the event instead");
        }
        return requested == AXEventClass.SILENT ? AXEventTier.NORMAL : AXEventTier.RARE;
    }

    /**
     * Whether {@code requested} needs a per-occurrence highlight to reach that class on a neutral list.
     *
     * <p>Only {@link AXEventClass#IMMEDIATE} does: interrupting the player requires being one step above the rare
     * baseline, and the highlight is the only per-occurrence way up. Everything else lands on a plain declaration.
     */
    public static boolean highlightedFor(AXEventClass requested) {
        return requested == AXEventClass.IMMEDIATE;
    }
}
