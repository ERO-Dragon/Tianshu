package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MossFrameGenerationResultTest {
    @Test
    void naturalEndCanBeConsumedAsSuccessfulGeneration() throws Exception {
        MossFrameGenerationResult result = MossFrameGenerationResult.naturalEnd(
                List.of(List.of(1, 2, 3, 4)),
                375
        );

        assertTrue(result.naturallyEnded());
        assertFalse(result.cancelled());
        assertEquals(1, result.generatedFrameCount());
        assertEquals(1, result.requireNaturalEnd(7).size());
    }

    @Test
    void cancellationRemainsDistinctFromGenerationFailure() {
        MossFrameGenerationResult result = MossFrameGenerationResult.cancelled(
                List.of(List.of(1, 2, 3, 4)),
                375
        );

        assertTrue(result.cancelled());
        assertFalse(result.naturallyEnded());
        assertThrows(MossGenerationCancelledException.class, () -> result.requireNaturalEnd(7));
    }

    @Test
    void frameLimitExhaustionFailsWithStableParameters() {
        MossFrameGenerationResult result = MossFrameGenerationResult.frameLimitReached(
                List.of(List.of(1), List.of(2), List.of(3)),
                3
        );

        TtsFailureException failure = assertThrows(
                TtsFailureException.class,
                () -> result.requireNaturalEnd(11)
        );

        assertEquals(TtsFailureCode.GENERATION_LIMIT_REACHED, failure.code());
        assertEquals(
                "TTS_MOSS_GENERATION_LIMIT_REACHED inputTokens=11 generatedFrames=3 maxFrames=3",
                failure.getMessage()
        );
    }
}
