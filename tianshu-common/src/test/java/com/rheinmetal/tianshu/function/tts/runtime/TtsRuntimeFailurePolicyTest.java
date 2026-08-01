package com.rheinmetal.tianshu.function.tts.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TtsRuntimeFailurePolicyTest {
    @Test
    void classifiesOrdinaryBackendFailureAndKeepsCause() {
        RuntimeException cause = new RuntimeException("backend failed");

        TtsFailure failure = TtsRuntimeFailurePolicy.classify(TtsFailureCode.SYNTHESIS_FAILED, cause);

        assertEquals(TtsFailureCode.SYNTHESIS_FAILED, failure.code());
        assertSame(cause, failure.cause());
    }

    @Test
    void preservesStructuredTtsFailureThroughBackendWrapping() {
        TtsFailureException cause = new TtsFailureException(
                TtsFailureCode.GENERATION_LIMIT_REACHED,
                "TTS_MOSS_GENERATION_LIMIT_REACHED inputTokens=11 generatedFrames=375 maxFrames=375"
        );

        TtsFailure failure = TtsRuntimeFailurePolicy.classify(
                TtsFailureCode.SYNTHESIS_FAILED,
                new IllegalStateException("TTS_MOSS_SYNTHESIS_FAILED", cause)
        );

        assertEquals(TtsFailureCode.GENERATION_LIMIT_REACHED, failure.code());
        assertEquals(cause.getMessage(), failure.message());
        assertSame(cause, failure.cause());
    }

    @Test
    void treatsNativeLinkageFailureAsBackendUnavailable() {
        UnsatisfiedLinkError cause = new UnsatisfiedLinkError("native runtime missing");

        TtsFailure failure = TtsRuntimeFailurePolicy.classify(
                TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE,
                cause
        );

        assertEquals(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, failure.code());
        assertSame(cause, failure.cause());
    }

    @Test
    void rethrowsFatalJvmFailure() {
        OutOfMemoryError fatal = new OutOfMemoryError("fatal");

        assertSame(fatal, assertThrows(
                OutOfMemoryError.class,
                () -> TtsRuntimeFailurePolicy.classify(TtsFailureCode.SYNTHESIS_FAILED, fatal)
        ));
    }

    @Test
    void rethrowsUnexpectedProgrammingError() {
        AssertionError programmingError = new AssertionError("broken invariant");

        assertSame(programmingError, assertThrows(
                AssertionError.class,
                () -> TtsRuntimeFailurePolicy.classify(TtsFailureCode.SYNTHESIS_FAILED, programmingError)
        ));
    }
}
