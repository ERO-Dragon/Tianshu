package com.rheinmetal.tianshu.neoforge.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The decoder trusts the peer for exactly nothing: these are the two declarations that used to reach an
 * allocation or a string table unchecked.
 */
class GameFactLimitsTest {

    @Test
    void aCountWithinTheBoundIsAccepted() {
        assertEquals(0, GameFactLimits.requireValueCount(0));
        assertEquals(GameFactLimits.MAX_VALUES, GameFactLimits.requireValueCount(GameFactLimits.MAX_VALUES));
    }

    @Test
    void aCountAboveTheBoundIsRejectedBeforeItCanBeAnAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> GameFactLimits.requireValueCount(GameFactLimits.MAX_VALUES + 1));
        assertThrows(IllegalArgumentException.class,
                () -> GameFactLimits.requireValueCount(Integer.MAX_VALUE));
    }

    @Test
    void aNegativeCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GameFactLimits.requireValueCount(-1));
    }

    @Test
    void validTextPassesThroughUnchanged() {
        assertEquals("minecraft:overworld", GameFactLimits.requireText("minecraft:overworld", "dimensionId"));
        assertEquals("", GameFactLimits.requireText(null, "value"));
    }

    @Test
    void theTextBoundMatchesWhatTheWireCanCarrySoValidDataIsNeverRejected() {
        String atLimit = "a".repeat(GameFactLimits.MAX_TEXT_LENGTH);
        assertDoesNotThrow(() -> GameFactLimits.requireText(atLimit, "value"));
        String overLimit = atLimit + "a";
        assertThrows(IllegalArgumentException.class, () -> GameFactLimits.requireText(overLimit, "value"));
    }
}
