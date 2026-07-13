package com.rheinmetal.tianshu.function.tts.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TtsFailureTest {
    @Test
    void keepsCodeAndNormalizesMessage() {
        TtsFailure failure = TtsFailure.of(TtsFailureCode.SYNTHESIS_FAILED, "  boom  ");

        assertEquals(TtsFailureCode.SYNTHESIS_FAILED, failure.code());
        assertEquals("boom", failure.message());
    }

    @Test
    void preservesOriginalFailureCause() {
        RuntimeException cause = new RuntimeException("backend failed");

        TtsFailure failure = TtsFailure.fromThrowable(TtsFailureCode.SYNTHESIS_FAILED, cause);

        assertSame(cause, failure.cause());
    }
}
