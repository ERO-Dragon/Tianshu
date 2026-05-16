package com.rheinmetal.tianshu.function.tts.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsOperationResultTest {
    @Test
    void acceptedResultCarriesRequestIdWithoutFailure() {
        TtsOperationResult result = TtsOperationResult.accepted("req-1");

        assertTrue(result.accepted());
        assertEquals("req-1", result.requestId());
        assertFalse(result.failureResult().isPresent());
    }

    @Test
    void rejectedResultCarriesStructuredFailure() {
        TtsFailure failure = TtsFailure.of(TtsFailureCode.EMPTY_TEXT, "empty");

        TtsOperationResult result = TtsOperationResult.rejected(failure);

        assertFalse(result.accepted());
        assertEquals(TtsFailureCode.EMPTY_TEXT, result.failure().code());
        assertEquals("empty", result.failure().message());
    }
}
