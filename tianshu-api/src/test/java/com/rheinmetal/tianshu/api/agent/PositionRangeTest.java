package com.rheinmetal.tianshu.api.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The coordinate bound lives in the value types, so every body tool inherits exactly one range. */
class PositionRangeTest {

    @Test
    void aBlockTargetBeyondTheWorldBorderIsRefused() {
        assertDoesNotThrow(() -> new BlockTarget("minecraft:overworld", BlockTarget.MAX_COORDINATE, 64, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockTarget("minecraft:overworld", BlockTarget.MAX_COORDINATE + 1, 64, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockTarget("minecraft:overworld", 0, -BlockTarget.MAX_COORDINATE - 1, 0));
    }

    @Test
    void aPositionBeyondTheWorldBorderIsRefusedAndNonFiniteStillIs() {
        assertDoesNotThrow(() -> new ActorPosition("minecraft:overworld", 0.5D, 64.0D, -BlockTarget.MAX_COORDINATE));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorPosition("minecraft:overworld", 2.0e9D, 64.0D, 0.0D));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorPosition("minecraft:overworld", Double.NaN, 64.0D, 0.0D));
    }
}