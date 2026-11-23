package com.rheinmetal.tianshu.dubhe.shared.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The nine-cell matrix and its inverse.
 *
 * <p>The matrix is the contract: the {@code FREQUENT} row contains no speaking class (H1), and the two cells that
 * used to collapse into {@code DROP} now fold into the window instead (FOLD).</p>
 */
class DubheEventPolicyTest {

    @Test
    void theNineCellsAreFixed() {
        assertClass(DubheEventClass.IMMEDIATE, DubheEventTier.RARE, DubheEventState.EMPHASIS);
        assertClass(DubheEventClass.DEFERRED, DubheEventTier.RARE, DubheEventState.NORMAL);
        assertClass(DubheEventClass.SILENT, DubheEventTier.RARE, DubheEventState.WEAKENED);

        assertClass(DubheEventClass.DEFERRED, DubheEventTier.NORMAL, DubheEventState.EMPHASIS);
        assertClass(DubheEventClass.SILENT, DubheEventTier.NORMAL, DubheEventState.NORMAL);
        assertClass(DubheEventClass.FOLD, DubheEventTier.NORMAL, DubheEventState.WEAKENED);

        assertClass(DubheEventClass.SILENT, DubheEventTier.FREQUENT, DubheEventState.EMPHASIS);
        assertClass(DubheEventClass.FOLD, DubheEventTier.FREQUENT, DubheEventState.NORMAL);
        assertClass(DubheEventClass.DROP, DubheEventTier.FREQUENT, DubheEventState.WEAKENED);
    }

    /** H1 as a property of the table: no state on the frequent row can reach the voice channel. */
    @Test
    void theFrequentRowCannotSpeak() {
        for (DubheEventState state : DubheEventState.values()) {
            assertEquals(false, DubheEventPolicy.classFor(DubheEventTier.FREQUENT, state).speaks(),
                    "frequent row x " + state);
        }
    }

    @Test
    void unknownInputsFallBackToTheMostConservativeCell() {
        assertEquals(DubheEventClass.FOLD, DubheEventPolicy.classFor(null, null),
                "the fallback is the frequent row with a normal state, which is window-only");
        assertEquals(DubheEventClass.FOLD, DubheEventPolicy.classFor(DubheEventTier.FREQUENT, null));
    }

    /** A producer states the behaviour it wants; the inverse must reproduce exactly that behaviour. */
    @Test
    void theInverseReproducesTheRequestedBehaviour() {
        for (DubheEventClass requested : DubheEventClass.values()) {
            if (requested == DubheEventClass.DROP) {
                continue;
            }
            DubheEventPolicy.Declaration declaration = DubheEventPolicy.declarationFor(requested);
            assertEquals(requested, DubheEventPolicy.classFor(declaration.tier(), declaration.state()),
                    "a producer asking for " + requested + " must land on it");
        }
    }

    /** "Nothing happens" is the absence of a submission, so it has no translation into the table. */
    @Test
    void nothingCanBeSubmittedAsDrop() {
        assertThrows(IllegalArgumentException.class, () -> DubheEventPolicy.declarationFor(DubheEventClass.DROP));
        assertThrows(IllegalArgumentException.class, () -> DubheEventPolicy.declarationFor(null));
    }

    private static void assertClass(DubheEventClass expected, DubheEventTier row, DubheEventState state) {
        assertEquals(expected, DubheEventPolicy.classFor(row, state), row + " x " + state);
    }
}