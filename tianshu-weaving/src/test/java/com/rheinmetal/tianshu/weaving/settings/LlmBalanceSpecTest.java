package com.rheinmetal.tianshu.weaving.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The balance descriptor as data: its stored form, the documented default path of each shape, and the
 * paths it refuses.
 *
 * <p>The refusal half matters as much as the read half — the credential is attached to whatever URL
 * this produces, so an absolute URL in this field would send a key to a host the entry never named.</p>
 */
class LlmBalanceSpecTest {

    @Test
    void everyShapeRoundTripsThroughItsStoredForm() {
        for (LlmBalanceSpec.BalanceWire wire : LlmBalanceSpec.BalanceWire.values()) {
            LlmBalanceSpec spec = new LlmBalanceSpec(wire, wire == LlmBalanceSpec.BalanceWire.NONE ? "" : "/custom/path");
            assertEquals(spec, LlmBalanceSpec.decode(spec.encode()), spec.encode());
        }
    }

    @Test
    void aBlankOrUnreadableStoredValueMeansNoOverride() {
        assertNull(LlmBalanceSpec.decode(null));
        assertNull(LlmBalanceSpec.decode(""));
        assertNull(LlmBalanceSpec.decode("   "));
        assertNull(LlmBalanceSpec.decode("NOPE;/user/balance"), "an unknown shape is not a value");
        assertNull(LlmBalanceSpec.decode("DEEPSEEK"), "the stored form is wire;path");
        assertNull(LlmBalanceSpec.decode("DEEPSEEK;/a;b"));
        assertNull(LlmBalanceSpec.decode("DEEPSEEK;user/balance"), "a path starts with a slash");
        assertNull(LlmBalanceSpec.decode("DEEPSEEK;https://elsewhere.example/balance"));
        assertNull(LlmBalanceSpec.decode("DEEPSEEK;/a b"));
    }

    @Test
    void eachShapeCarriesItsDocumentedDefaultPath() {
        assertEquals("/user/balance", new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.DEEPSEEK, "").effectivePath());
        assertEquals("/users/me/balance", new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.MOONSHOT, "").effectivePath());
        assertEquals("/dashboard/billing/subscription",
                new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.OPENAI_BILLING, "").effectivePath());
        assertEquals("", LlmBalanceSpec.NONE.effectivePath());
        assertEquals("/custom", new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.DEEPSEEK, "/custom").effectivePath(),
                "a configured path wins over the documented one");
    }

    @Test
    void onlyAProbeableShapeClaimsToHaveAQuery() {
        assertFalse(LlmBalanceSpec.NONE.supportsQuery());
        assertTrue(new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.DEEPSEEK, "").supportsQuery());
        assertTrue(new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.OPENAI_BILLING, "").supportsQuery());
    }

    @Test
    void aNullWireIsTheNoneShapeRatherThanACrash() {
        LlmBalanceSpec spec = new LlmBalanceSpec(null, null);
        assertEquals(LlmBalanceSpec.BalanceWire.NONE, spec.wire());
        assertEquals("", spec.path());
        assertFalse(spec.supportsQuery());
    }
}