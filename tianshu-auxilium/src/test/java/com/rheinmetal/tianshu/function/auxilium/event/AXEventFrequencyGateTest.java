package com.rheinmetal.tianshu.function.auxilium.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Frequency correction of the tier: two configurable thresholds walk a kind towards "this happens all the time".
 *
 * <p>The tier is the frequency, so this is where repetition is judged: a rare kind that keeps happening becomes a normal
 * one, and a normal kind that keeps happening becomes a frequent one. Nothing here names a handling class — that is
 * {@link AXEventPolicy}'s job, and it holds no threshold at all.
 */
class AXEventFrequencyGateTest {
    private static final long START = 1_000_000L;
    private static final long WINDOW = AXEventSettings.DEFAULT_FREQUENCY_WINDOW_MILLIS;
    private static final String KIND = "tianshu:death";

    private final AXEventFrequencyGate gate = new AXEventFrequencyGate(AXEventSettings.DEFAULT);

    @Test
    void aSingleRareOccurrenceKeepsItsTier() {
        assertEquals(AXEventTier.RARE, gate.correctedTier(KIND, AXEventTier.RARE, START));
        assertEquals(AXEventTier.NORMAL, gate.correctedTier("tianshu:respawn", AXEventTier.NORMAL, START));
    }

    @Test
    void theThirdOccurrenceMakesARareKindNormal() {
        assertEquals(AXEventTier.RARE, gate.correctedTier(KIND, AXEventTier.RARE, START));
        assertEquals(AXEventTier.RARE, gate.correctedTier(KIND, AXEventTier.RARE, START + 1_000L));
        assertEquals(AXEventTier.NORMAL, gate.correctedTier(KIND, AXEventTier.RARE, START + 2_000L),
                "the third occurrence inside the window is no longer rare");
        assertEquals(AXEventTier.NORMAL, gate.correctedTier(KIND, AXEventTier.RARE, START + 3_000L));
    }

    @Test
    void theSixthOccurrenceMakesItFrequent() {
        for (int occurrence = 1; occurrence <= 5; occurrence++) {
            gate.correctedTier(KIND, AXEventTier.RARE, START + occurrence * 100L);
        }

        assertEquals(AXEventTier.FREQUENT, gate.correctedTier(KIND, AXEventTier.RARE, START + 600L),
                "at the sixth occurrence the kind has walked both steps");
    }

    @Test
    void aFrequentKindIsNeverCorrected() {
        for (int occurrence = 1; occurrence <= 12; occurrence++) {
            assertEquals(AXEventTier.FREQUENT,
                    gate.correctedTier(KIND, AXEventTier.FREQUENT, START + occurrence * 100L));
        }
    }

    @Test
    void theWindowSlidesSoAnOldBurstNoLongerCounts() {
        for (int occurrence = 1; occurrence <= 3; occurrence++) {
            gate.correctedTier(KIND, AXEventTier.RARE, START + occurrence * 100L);
        }

        assertEquals(AXEventTier.RARE,
                gate.correctedTier(KIND, AXEventTier.RARE, START + WINDOW + 5_000L),
                "every earlier occurrence has left the window");
    }

    @Test
    void countingIsPerKind() {
        gate.correctedTier(KIND, AXEventTier.RARE, START);
        gate.correctedTier(KIND, AXEventTier.RARE, START + 100L);

        assertEquals(AXEventTier.RARE,
                gate.correctedTier("tianshu:rare_item", AXEventTier.RARE, START + 200L),
                "another kind starts with an empty window");
    }

    /** Both thresholds are configuration, not constants of the judgement. */
    @Test
    void theThresholdsComeFromTheSettings() {
        AXEventSettings impatient = new AXEventSettings() {
            @Override
            public int rareToNormalThreshold() {
                return 2;
            }

            @Override
            public int normalToFrequentThreshold() {
                return 4;
            }
        };
        AXEventFrequencyGate configured = new AXEventFrequencyGate(impatient);

        assertEquals(AXEventTier.RARE, configured.correctedTier(KIND, AXEventTier.RARE, START));
        assertEquals(AXEventTier.NORMAL, configured.correctedTier(KIND, AXEventTier.RARE, START + 100L),
                "the second occurrence already crosses the configured rare threshold");
        assertEquals(AXEventTier.NORMAL, configured.correctedTier(KIND, AXEventTier.RARE, START + 200L),
                "three is still below the configured normal threshold");
        assertEquals(AXEventTier.FREQUENT, configured.correctedTier(KIND, AXEventTier.RARE, START + 300L),
                "the fourth occurrence crosses the configured normal threshold");
    }
}
