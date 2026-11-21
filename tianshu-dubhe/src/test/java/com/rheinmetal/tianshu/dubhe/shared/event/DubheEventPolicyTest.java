package com.rheinmetal.tianshu.dubhe.shared.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The policy matrix, expanded from the ladder rather than written as nine cases.
 *
 * <p>The ladder is the thing being pinned: baseline per tier, one step for the list, both ends clamped. The nine
 * cells below are the expansion the design document states, so a change to either the baselines or the clamping
 * shows up here as a broken cell instead of as a quietly different behaviour.
 */
class DubheEventPolicyTest {

    @Test
    void theMatrixExpandsFromTheLadder() {
        // RARE: rare enough that a whitelist entry is worth interrupting for.
        assertClass(DubheEventClass.IMMEDIATE, DubheEventTier.RARE, DubheEventList.WHITELIST);
        assertClass(DubheEventClass.DEFERRED, DubheEventTier.RARE, DubheEventList.NEUTRAL);
        assertClass(DubheEventClass.SILENT, DubheEventTier.RARE, DubheEventList.BLACKLIST);

        // NORMAL: worth saying during a lull, silent when marked, never heard when both.
        assertClass(DubheEventClass.DEFERRED, DubheEventTier.NORMAL, DubheEventList.WHITELIST);
        assertClass(DubheEventClass.SILENT, DubheEventTier.NORMAL, DubheEventList.NEUTRAL);
        assertClass(DubheEventClass.DROP, DubheEventTier.NORMAL, DubheEventList.BLACKLIST);

        // FREQUENT: the baseline is already dropped; a whitelist entry lifts it back to a counted history line.
        assertClass(DubheEventClass.SILENT, DubheEventTier.FREQUENT, DubheEventList.WHITELIST);
        assertClass(DubheEventClass.DROP, DubheEventTier.FREQUENT, DubheEventList.NEUTRAL);
        assertClass(DubheEventClass.DROP, DubheEventTier.FREQUENT, DubheEventList.BLACKLIST);
    }

    /** A blacklisted rare event is still worth a counted history line: it is quieted, not thrown away. */
    @Test
    void aBlacklistedRareEventIsSilentNotDropped() {
        assertEquals(DubheEventClass.SILENT, DubheEventPolicy.classFor(DubheEventTier.RARE, DubheEventList.BLACKLIST));
        assertEquals(DubheEventPolicy.baselineFor(DubheEventTier.RARE).downgraded(),
                DubheEventPolicy.classFor(DubheEventTier.RARE, DubheEventList.BLACKLIST),
                "one step down from the rare baseline is silent");
    }

    @Test
    void theLadderClampsAtBothEnds() {
        assertEquals(DubheEventClass.IMMEDIATE, DubheEventClass.IMMEDIATE.upgraded());
        assertEquals(DubheEventClass.DROP, DubheEventClass.DROP.downgraded());
        assertEquals(DubheEventClass.IMMEDIATE, DubheEventClass.DEFERRED.upgraded());
        assertEquals(DubheEventClass.DROP, DubheEventClass.SILENT.downgraded());
    }

    @Test
    void aNeutralKindFollowsItsTierBaseline() {
        for (DubheEventTier tier : DubheEventTier.values()) {
            assertEquals(DubheEventPolicy.baselineFor(tier), DubheEventPolicy.classFor(tier, DubheEventList.NEUTRAL));
        }
    }

    /**
     * The event entry external modules submit to states a behaviour, not a frequency; the translation back into a
     * declaration has to reproduce exactly that behaviour, otherwise a producer asking for "say it now" would get a
     * counted history line instead.
     */
    @Test
    void theInverseOfTheMatrixReproducesTheRequestedBehaviour() {
        for (DubheEventClass requested : DubheEventClass.values()) {
            if (requested == DubheEventClass.DROP) {
                continue;
            }
            DubheEventList list = DubheEventPolicy.highlightedFor(requested) ? DubheEventList.WHITELIST : DubheEventList.NEUTRAL;
            assertEquals(requested, DubheEventPolicy.classFor(DubheEventPolicy.tierFor(requested), list),
                    "a producer asking for " + requested + " must land on it");
        }
    }

    /** "Nothing happens" is the absence of a submission, so it has no translation into the table. */
    @Test
    void nothingCanBeSubmittedAsDrop() {
        assertThrows(IllegalArgumentException.class, () -> DubheEventPolicy.tierFor(DubheEventClass.DROP));
        assertThrows(IllegalArgumentException.class, () -> DubheEventPolicy.tierFor(null));
    }

    private static void assertClass(DubheEventClass expected, DubheEventTier tier, DubheEventList list) {
        assertEquals(expected, DubheEventPolicy.classFor(tier, list), tier + " x " + list);
    }
}
