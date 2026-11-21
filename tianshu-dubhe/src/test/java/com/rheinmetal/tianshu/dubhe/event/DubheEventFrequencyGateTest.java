package com.rheinmetal.tianshu.dubhe.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Frequency correction of the tier: two configurable thresholds walk a kind towards "this happens all the time".
 *
 * <p>The tier is the frequency, so this is where repetition is judged: a rare kind that keeps happening becomes a normal
 * one, and a normal kind that keeps happening becomes a frequent one. Nothing here names a handling class — that is
 * {@link DubheEventPolicy}'s job, and it holds no threshold at all.
 */
class DubheEventFrequencyGateTest {
    private static final long START = 1_000_000L;
    private static final long WINDOW = DubheEventSettings.DEFAULT_FREQUENCY_WINDOW_MILLIS;
    private static final String KIND = "tianshu:death";

    private final DubheEventFrequencyGate gate = new DubheEventFrequencyGate(DubheEventSettings.DEFAULT);

    @Test
    void aSingleRareOccurrenceKeepsItsTier() {
        assertEquals(DubheEventTier.RARE, gate.correctedTier(KIND, DubheEventTier.RARE, START));
        assertEquals(DubheEventTier.NORMAL, gate.correctedTier("tianshu:damage_taken", DubheEventTier.NORMAL, START));
    }

    @Test
    void theThirdOccurrenceMakesARareKindNormal() {
        assertEquals(DubheEventTier.RARE, gate.correctedTier(KIND, DubheEventTier.RARE, START));
        assertEquals(DubheEventTier.RARE, gate.correctedTier(KIND, DubheEventTier.RARE, START + 1_000L));
        assertEquals(DubheEventTier.NORMAL, gate.correctedTier(KIND, DubheEventTier.RARE, START + 2_000L),
                "the third occurrence inside the window is no longer rare");
        assertEquals(DubheEventTier.NORMAL, gate.correctedTier(KIND, DubheEventTier.RARE, START + 3_000L));
    }

    @Test
    void theSixthOccurrenceMakesItFrequent() {
        for (int occurrence = 1; occurrence <= 5; occurrence++) {
            gate.correctedTier(KIND, DubheEventTier.RARE, START + occurrence * 100L);
        }

        assertEquals(DubheEventTier.FREQUENT, gate.correctedTier(KIND, DubheEventTier.RARE, START + 600L),
                "at the sixth occurrence the kind has walked both steps");
    }

    @Test
    void aFrequentKindIsNeverCorrected() {
        for (int occurrence = 1; occurrence <= 12; occurrence++) {
            assertEquals(DubheEventTier.FREQUENT,
                    gate.correctedTier(KIND, DubheEventTier.FREQUENT, START + occurrence * 100L));
        }
    }

    @Test
    void theWindowSlidesSoAnOldBurstNoLongerCounts() {
        for (int occurrence = 1; occurrence <= 3; occurrence++) {
            gate.correctedTier(KIND, DubheEventTier.RARE, START + occurrence * 100L);
        }

        assertEquals(DubheEventTier.RARE,
                gate.correctedTier(KIND, DubheEventTier.RARE, START + WINDOW + 5_000L),
                "every earlier occurrence has left the window");
    }

    @Test
    void countingIsPerKind() {
        gate.correctedTier(KIND, DubheEventTier.RARE, START);
        gate.correctedTier(KIND, DubheEventTier.RARE, START + 100L);

        assertEquals(DubheEventTier.RARE,
                gate.correctedTier("tianshu:rare_item", DubheEventTier.RARE, START + 200L),
                "another kind starts with an empty window");
    }

    /** Both thresholds are configuration, not constants of the judgement. */
    @Test
    void theThresholdsComeFromTheSettings() {
        DubheEventSettings impatient = new DubheEventSettings() {
            @Override
            public int rareToNormalThreshold() {
                return 2;
            }

            @Override
            public int normalToFrequentThreshold() {
                return 4;
            }
        };
        DubheEventFrequencyGate configured = new DubheEventFrequencyGate(impatient);

        assertEquals(DubheEventTier.RARE, configured.correctedTier(KIND, DubheEventTier.RARE, START));
        assertEquals(DubheEventTier.NORMAL, configured.correctedTier(KIND, DubheEventTier.RARE, START + 100L),
                "the second occurrence already crosses the configured rare threshold");
        assertEquals(DubheEventTier.NORMAL, configured.correctedTier(KIND, DubheEventTier.RARE, START + 200L),
                "three is still below the configured normal threshold");
        assertEquals(DubheEventTier.FREQUENT, configured.correctedTier(KIND, DubheEventTier.RARE, START + 300L),
                "the fourth occurrence crosses the configured normal threshold");
    }
}
