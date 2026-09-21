package com.rheinmetal.tianshu.neoforge.ui.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PresenceHudElementTimingTest {
    @Test
    void zeroStateEntryUsesTheFrameTimestampWithoutReadingAnotherClock() {
        PresenceHudElementTiming timing = new PresenceHudElementTiming(240L, 0L);

        assertEquals(240L, timing.updatedAtMillis());
        assertEquals(240L, timing.stateEnteredAtMillis());
    }

    @Test
    void negativeTimestampsAreClampedToTheRenderTimeDomain() {
        PresenceHudElementTiming timing = new PresenceHudElementTiming(-1L, -2L);

        assertEquals(0L, timing.updatedAtMillis());
        assertEquals(0L, timing.stateEnteredAtMillis());
    }
}
