package com.rheinmetal.tianshu.function.weaving.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeavingContextTiersTest {

    @Test
    void theSelectableTiersEndAtOneMillion() {
        int[] tiers = WeavingContextTiers.values();

        assertEquals(5, tiers.length);
        assertEquals(WeavingContextTiers.TIER_1M, tiers[tiers.length - 1]);
        assertTrue(WeavingContextTiers.isTier(WeavingContextTiers.TIER_200K));
        assertTrue(WeavingContextTiers.isTier(WeavingContextTiers.TIER_1M));
        assertFalse(WeavingContextTiers.isTier(272_000), "a preset window between tiers is not a tier");
    }

    @Test
    void aModelWithoutADeclaredWindowStartsAtTwoHundredK() {
        assertEquals(WeavingContextTiers.TIER_200K, WeavingContextTiers.defaultWindow());
        assertEquals(WeavingContextTiers.minimumTier(), WeavingContextTiers.TIER_50K);
    }

    @Test
    void theStorableRangeIsDefinedOnce() {
        assertTrue(WeavingContextTiers.isValidWindow(WeavingContextTiers.TIER_50K));
        assertTrue(WeavingContextTiers.isValidWindow(272_000));
        assertFalse(WeavingContextTiers.isValidWindow(WeavingContextTiers.TIER_50K - 1));
        assertFalse(WeavingContextTiers.isValidWindow(WeavingConfiguration.MAX_LLM_CONTEXT_WINDOW_TOKENS + 1));
    }
}