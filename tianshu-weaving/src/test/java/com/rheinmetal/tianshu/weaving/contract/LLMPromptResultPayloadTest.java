package com.rheinmetal.tianshu.weaving.contract;

import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload.TokenUsagePayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terminal result's shape: what each status normalizes to, and which statuses may carry the
 * truncation mark.
 *
 * <p>Truncation is a statement about an answer the provider <em>completed</em> at its cap. A cancelled
 * or failed call carries a prefix of an interrupted request instead, and its status already says that —
 * so the flag is dropped there even if a caller asks for it.</p>
 */
class LLMPromptResultPayloadTest {

    @Test
    void everyFactoryLeavesTruncationOffUnlessItIsAskedFor() {
        assertFalse(LLMPromptResultPayload.completed("r", "text").truncated());
        assertFalse(LLMPromptResultPayload.completed("r", "text", "", TokenUsagePayload.empty()).truncated());
        assertFalse(LLMPromptResultPayload.cancelled("r", "text").truncated());
        assertFalse(LLMPromptResultPayload.failed("r", "E", "m").truncated());
    }

    @Test
    void aCompletedAnswerCarriesTheTruncationMark() {
        LLMPromptResultPayload payload = LLMPromptResultPayload.completed("r", "half", "", TokenUsagePayload.empty(), true);

        assertTrue(payload.truncated());
        assertEquals("COMPLETED", payload.status());
        assertEquals("half", payload.text(), "the partial text is still the answer");
        assertNull(payload.errorCode());
    }

    @Test
    void anInterruptedAnswerCannotClaimToBeTruncated() {
        LLMPromptResultPayload cancelled = new LLMPromptResultPayload(
                "r", "CANCELLED", "half", "", null, null, TokenUsagePayload.empty(), true);
        LLMPromptResultPayload failed = new LLMPromptResultPayload(
                "r", "FAILED", "half", "", "E", "m", TokenUsagePayload.empty(), true);

        assertFalse(cancelled.truncated(), "a cancel is not a cap");
        assertFalse(failed.truncated(), "a failure is not a cap");
    }

    @Test
    void anUnknownStatusNormalizesToFailedAndLosesTheMark() {
        LLMPromptResultPayload payload = new LLMPromptResultPayload(
                "r", "weird", "half", "", null, null, TokenUsagePayload.empty(), true);

        assertEquals("FAILED", payload.status());
        assertFalse(payload.truncated());
    }
}