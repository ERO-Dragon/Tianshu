package com.rheinmetal.tianshu.function.tts.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsControlResultTest {
    @Test
    void acceptedResultCarriesActionAndAffectedSessions() {
        TtsControlResult result = TtsControlResult.accepted(TtsControlAction.STOP_SOURCE, 3);

        assertTrue(result.accepted());
        assertEquals(TtsControlAction.STOP_SOURCE, result.action());
        assertEquals(3, result.affectedSessions());
        assertFalse(result.failureResult().isPresent());
    }

    @Test
    void rejectedResultCarriesStructuredFailure() {
        TtsFailure failure = TtsFailure.of(TtsFailureCode.INVALID_REQUEST, "invalid");

        TtsControlResult result = TtsControlResult.rejected(TtsControlAction.STOP_REQUEST, failure);

        assertFalse(result.accepted());
        assertEquals(TtsControlAction.STOP_REQUEST, result.action());
        assertEquals(TtsFailureCode.INVALID_REQUEST, result.failure().code());
        assertEquals("invalid", result.failure().message());
    }

    @Test
    void constructorNormalizesDefaults() {
        TtsControlResult result = new TtsControlResult(true, null, -1, null, 0L);

        assertEquals(TtsControlAction.STOP_ALL, result.action());
        assertEquals(0, result.affectedSessions());
        assertTrue(result.occurredAtMillis() > 0L);
    }
}
