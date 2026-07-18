package com.rheinmetal.tianshu.function.tts.voice;

import com.rheinmetal.tianshu.function.tts.runtime.TtsFailure;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.protocol.payload.TtsVoiceOptions;

import java.util.Optional;
import java.util.function.Predicate;

public final class TtsVoiceRequestValidator {
    private TtsVoiceRequestValidator() {
    }

    public static Optional<TtsFailure> validate(
            TtsVoiceOptions options,
            boolean voiceCloneSupported,
            Predicate<String> voiceExists
    ) {
        String voiceId = options == null ? "" : options.voiceId();
        if (voiceId.isBlank()) {
            return Optional.empty();
        }
        boolean available = voiceCloneSupported
                && voiceExists != null
                && voiceExists.test(voiceId);
        if (available) {
            return Optional.empty();
        }
        return Optional.of(TtsFailure.of(
                TtsFailureCode.VOICE_CLONE_UNAVAILABLE,
                "TTS voiceId is unavailable: " + voiceId
        ));
    }
}
