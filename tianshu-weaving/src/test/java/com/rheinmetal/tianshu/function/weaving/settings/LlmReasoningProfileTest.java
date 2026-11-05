package com.rheinmetal.tianshu.function.weaving.settings;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmReasoningProfileTest {

    @Test
    void tierZeroIsTheNonThinkingSlotAndTheRestAreProviderNames() {
        LlmReasoningProfile profile = new LlmReasoningProfile(
                LlmReasoningWire.DEEPSEEK_THINKING, true, List.of("low", "high", "max"), 1);

        assertEquals(4, profile.tierCount());
        assertNull(profile.levelName(0), "tier 0 is the non-thinking slot");
        assertEquals("low", profile.levelName(1));
        assertEquals("max", profile.levelName(3));
        assertEquals(1, profile.defaultTier());
    }

    @Test
    void aLadderWithoutTheNonThinkingSlotStartsAtItsFirstName() {
        LlmReasoningProfile profile = new LlmReasoningProfile(
                LlmReasoningWire.OPENAI_EFFORT, false, List.of("low", "medium", "high"), 0);

        assertEquals(3, profile.tierCount());
        assertEquals("low", profile.levelName(0));
        assertEquals("high", profile.levelName(2));
    }

    @Test
    void aStaleTierClampsToTheLadderInsteadOfFailing() {
        LlmReasoningProfile profile = new LlmReasoningProfile(
                LlmReasoningWire.OPENAI_EFFORT, false, List.of("low", "high"), 0);

        assertEquals(1, profile.clampTier(99));
        assertEquals("high", profile.levelName(99));
        assertEquals(0, profile.clampTier(-5));
    }

    @Test
    void noWireMeansNoLadderWhateverElseIsGiven() {
        LlmReasoningProfile profile = new LlmReasoningProfile(
                LlmReasoningWire.NONE, true, List.of("low", "high"), 2);

        assertEquals(0, profile.tierCount());
        assertFalse(profile.hasControl());
        assertSame(LlmReasoningProfile.NONE.wire(), profile.wire());
    }

    @Test
    void aLadderSurvivesAnEncodeDecodeRoundTrip() {
        LlmReasoningProfile profile = new LlmReasoningProfile(
                LlmReasoningWire.DEEPSEEK_THINKING, true, List.of("low", "high", "max"), 2);

        assertEquals(profile, LlmReasoningProfile.decode(profile.encode()));
    }

    @Test
    void unreadableStoredLaddersDecodeToNullSoAHandEditCannotCrashThePage() {
        assertNull(LlmReasoningProfile.decode(null));
        assertNull(LlmReasoningProfile.decode(""));
        assertNull(LlmReasoningProfile.decode("nonsense"));
        assertNull(LlmReasoningProfile.decode("NOT_A_WIRE;1;low;0"));
        assertNull(LlmReasoningProfile.decode("OPENAI_EFFORT;0;low;not-a-number"));
    }

    @Test
    void anEmptyStoredLadderDecodesToTheNoControlProfile() {
        LlmReasoningProfile decoded = LlmReasoningProfile.decode("NONE;0;;0");

        assertTrue(decoded != null);
        assertEquals(0, decoded.tierCount());
    }
}