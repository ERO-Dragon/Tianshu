package com.rheinmetal.tianshu.function.weaving.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmWireCompatTest {

    @Test
    void theDefaultIsTheStrictOpenAiShape() {
        assertTrue(LlmWireCompat.DEFAULT.includeStreamUsage());
        assertEquals(LlmWireCompat.MaxTokensField.MAX_TOKENS, LlmWireCompat.DEFAULT.maxTokensField());
    }

    @Test
    void roundTripsThroughItsStoredForm() {
        LlmWireCompat compact = new LlmWireCompat(false, LlmWireCompat.MaxTokensField.MAX_COMPLETION_TOKENS);

        assertEquals(compact, LlmWireCompat.decode(compact.encode()));
        assertEquals(LlmWireCompat.DEFAULT, LlmWireCompat.decode(LlmWireCompat.DEFAULT.encode()));
    }

    @Test
    void unreadableTextDecodesToNull() {
        assertNull(LlmWireCompat.decode(null));
        assertNull(LlmWireCompat.decode(""));
        assertNull(LlmWireCompat.decode("1"));
        assertNull(LlmWireCompat.decode("1;NOPE"));
        assertNull(LlmWireCompat.decode("maybe;MAX_TOKENS"));
    }

    @Test
    void theWireNamesAreTheProviderFieldNames() {
        assertEquals("max_tokens", LlmWireCompat.MaxTokensField.MAX_TOKENS.wireName());
        assertEquals("max_completion_tokens", LlmWireCompat.MaxTokensField.MAX_COMPLETION_TOKENS.wireName());
    }

    @Test
    void aNullFieldFallsBackToMaxTokens() {
        LlmWireCompat compat = new LlmWireCompat(true, null);

        assertTrue(compat.includeStreamUsage());
        assertEquals(LlmWireCompat.MaxTokensField.MAX_TOKENS, compat.maxTokensField());
    }

    @Test
    void aDisabledStreamUsageEncodesAsZero() {
        assertFalse(new LlmWireCompat(false, LlmWireCompat.MaxTokensField.MAX_TOKENS).encode().startsWith("1"));
    }
}