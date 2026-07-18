package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPlacement;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsTextInputMode;
import com.rheinmetal.tianshu.protocol.payload.TtsVoiceOptions;
import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;

public class VoiceNotificationService {
    private final ModuleProtocolAccess runtime;

    public VoiceNotificationService(ModuleProtocolAccess runtime) {
        this.runtime = runtime;
    }

    public void speakAlert(String text) {
        submitAlert(text, TtsPlaybackPlacement.INSERT_AFTER_SENTENCE, Priority.HIGH);
    }

    public void speakAlertWithInterrupt(String text) {
        submitAlert(text, TtsPlaybackPlacement.CANCEL_SENTENCE_AND_PLAY, Priority.CRITICAL);
    }

    private void submitAlert(String text, TtsPlaybackPlacement placement, Priority priority) {
        runtime.submit(EnvelopeBuilder.commandToCapability(
                        TtsProtocolAdapter.SOURCE_ID,
                        ProtocolCapabilities.TTS_SPEAK,
                        PayloadType.TTS_TEXT,
                        new TtsSpeakPayload(
                                text,
                                0,
                                0L,
                                placement,
                                TtsTextInputMode.DOCUMENT,
                                TtsVoiceOptions.defaults()
                        )
                )
                .priority(priority)
                .build());
    }
}
