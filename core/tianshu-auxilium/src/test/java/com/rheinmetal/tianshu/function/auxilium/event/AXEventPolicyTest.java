package com.rheinmetal.tianshu.function.auxilium.event;

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
class AXEventPolicyTest {

    @Test
    void theMatrixExpandsFromTheLadder() {
        // RARE: rare enough that a whitelist entry is worth interrupting for.
        assertClass(AXEventClass.IMMEDIATE, AXEventTier.RARE, AXEventList.WHITELIST);
        assertClass(AXEventClass.DEFERRED, AXEventTier.RARE, AXEventList.NEUTRAL);
        assertClass(AXEventClass.SILENT, AXEventTier.RARE, AXEventList.BLACKLIST);

        // NORMAL: worth saying during a lull, silent when marked, never heard when both.
        assertClass(AXEventClass.DEFERRED, AXEventTier.NORMAL, AXEventList.WHITELIST);
        assertClass(AXEventClass.SILENT, AXEventTier.NORMAL, AXEventList.NEUTRAL);
        assertClass(AXEventClass.DROP, AXEventTier.NORMAL, AXEventList.BLACKLIST);

        // FREQUENT: the baseline is already dropped; a whitelist entry lifts it back to a counted history line.
        assertClass(AXEventClass.SILENT, AXEventTier.FREQUENT, AXEventList.WHITELIST);
        assertClass(AXEventClass.DROP, AXEventTier.FREQUENT, AXEventList.NEUTRAL);
        assertClass(AXEventClass.DROP, AXEventTier.FREQUENT, AXEventList.BLACKLIST);
    }

    /** A blacklisted rare event is still worth a counted history line: it is quieted, not thrown away. */
    @Test
    void aBlacklistedRareEventIsSilentNotDropped() {
        assertEquals(AXEventClass.SILENT, AXEventPolicy.classFor(AXEventTier.RARE, AXEventList.BLACKLIST));
        assertEquals(AXEventPolicy.baselineFor(AXEventTier.RARE).downgraded(),
                AXEventPolicy.classFor(AXEventTier.RARE, AXEventList.BLACKLIST),
                "one step down from the rare baseline is silent");
    }

    @Test
    void theLadderClampsAtBothEnds() {
        assertEquals(AXEventClass.IMMEDIATE, AXEventClass.IMMEDIATE.upgraded());
        assertEquals(AXEventClass.DROP, AXEventClass.DROP.downgraded());
        assertEquals(AXEventClass.IMMEDIATE, AXEventClass.DEFERRED.upgraded());
        assertEquals(AXEventClass.DROP, AXEventClass.SILENT.downgraded());
    }

    @Test
    void aNeutralKindFollowsItsTierBaseline() {
        for (AXEventTier tier : AXEventTier.values()) {
            assertEquals(AXEventPolicy.baselineFor(tier), AXEventPolicy.classFor(tier, AXEventList.NEUTRAL));
        }
    }

    /**
     * The event entry external modules submit to states a behaviour, not a frequency; the translation back into a
     * declaration has to reproduce exactly that behaviour, otherwise a producer asking for "say it now" would get a
     * counted history line instead.
     */
    @Test
    void theInverseOfTheMatrixReproducesTheRequestedBehaviour() {
        for (AXEventClass requested : AXEventClass.values()) {
            if (requested == AXEventClass.DROP) {
                continue;
            }
            AXEventList list = AXEventPolicy.highlightedFor(requested) ? AXEventList.WHITELIST : AXEventList.NEUTRAL;
            assertEquals(requested, AXEventPolicy.classFor(AXEventPolicy.tierFor(requested), list),
                    "a producer asking for " + requested + " must land on it");
        }
    }

    /** "Nothing happens" is the absence of a submission, so it has no translation into the table. */
    @Test
    void nothingCanBeSubmittedAsDrop() {
        assertThrows(IllegalArgumentException.class, () -> AXEventPolicy.tierFor(AXEventClass.DROP));
        assertThrows(IllegalArgumentException.class, () -> AXEventPolicy.tierFor(null));
    }

    private static void assertClass(AXEventClass expected, AXEventTier tier, AXEventList list) {
        assertEquals(expected, AXEventPolicy.classFor(tier, list), tier + " x " + list);
    }
}
