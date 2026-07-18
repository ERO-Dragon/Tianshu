package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPlacement;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSynthesisRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsTextInputMode;
import com.rheinmetal.tianshu.protocol.payload.TtsVoiceOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TtsProtocolPayloadTest {
    @Test
    void speakPayloadCarriesExplicitInputModeAndVoiceOverrides() {
        TtsVoiceOptions voice = new TtsVoiceOptions("ax:default", 1.15F, 2);
        TtsSpeakPayload payload = new TtsSpeakPayload(
                "  hello  ",
                7,
                11L,
                TtsPlaybackPlacement.INSERT_AFTER_SENTENCE,
                TtsTextInputMode.SENTENCE_STREAM,
                voice
        );

        assertEquals("  hello  ", payload.text());
        assertEquals(TtsTextInputMode.SENTENCE_STREAM, payload.inputMode());
        assertEquals("ax:default", payload.voice().voiceId());
        assertEquals(1.15F, payload.voice().speed());
        assertEquals(2, payload.voice().speakerId());
    }

    @Test
    void synthesisPayloadUsesStructuredVoiceOverrides() {
        TtsSynthesisRequestPayload payload = new TtsSynthesisRequestPayload(
                "npc-1",
                "line",
                true,
                10_000L,
                new TtsVoiceOptions("npc:voice", null, null)
        );

        assertEquals("npc:voice", payload.voice().voiceId());
        assertNull(payload.voice().speed());
        assertNull(payload.voice().speakerId());
    }

    @Test
    void voiceOptionsRejectInvalidOverrides() {
        assertThrows(IllegalArgumentException.class, () -> new TtsVoiceOptions("", 0.05F, null));
        assertThrows(IllegalArgumentException.class, () -> new TtsVoiceOptions("", null, -1));
    }
}
