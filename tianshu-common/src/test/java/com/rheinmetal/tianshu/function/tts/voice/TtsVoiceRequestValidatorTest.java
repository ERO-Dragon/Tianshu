package com.rheinmetal.tianshu.function.tts.voice;

import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.protocol.payload.TtsVoiceOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsVoiceRequestValidatorTest {
    @Test
    void explicitUnknownVoiceIsRejectedInsteadOfFallingBack() {
        var failure = TtsVoiceRequestValidator.validate(
                new TtsVoiceOptions("missing", null, null),
                true,
                ignored -> false
        );

        assertTrue(failure.isPresent());
        assertEquals(TtsFailureCode.VOICE_CLONE_UNAVAILABLE, failure.orElseThrow().code());
    }

    @Test
    void blankVoiceUsesModelDefaultAndRegisteredVoiceIsAccepted() {
        assertTrue(TtsVoiceRequestValidator.validate(
                TtsVoiceOptions.defaults(),
                false,
                ignored -> false
        ).isEmpty());
        assertTrue(TtsVoiceRequestValidator.validate(
                new TtsVoiceOptions("known", null, null),
                true,
                "known"::equals
        ).isEmpty());
    }
}
