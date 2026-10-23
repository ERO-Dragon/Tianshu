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
}
