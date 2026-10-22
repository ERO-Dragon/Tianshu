package com.rheinmetal.tianshu.protocol.voice;

import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
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
        speakQueued(text, TtsPlaybackPlacement.INSERT_AFTER_SENTENCE, Priority.HIGH);
    }

    public void speakAlertWithInterrupt(String text) {
        speakQueued(text, TtsPlaybackPlacement.CANCEL_SENTENCE_AND_PLAY, Priority.CRITICAL);
    }

    /**
     * Speaks one out-of-band sentence with an explicit queue placement.
     *
     * <p>This is the single submission point of the voice channel: the payload type, capability and
     * input mode are the ones the TTS module already consumes, so a caller varies only <em>where in the
     * queue</em> the sentence lands and how urgent it is. Nothing here interrupts by itself: an
     * interrupting placement has to be asked for explicitly.</p>
     */
    public void speakQueued(String text, TtsPlaybackPlacement placement, Priority priority) {
        runtime.submit(EnvelopeBuilder.commandToCapability(
                        ProtocolModuleIds.TTS,
                        ProtocolCapabilities.TTS_SPEAK,
                        PayloadType.TTS_TEXT,
                        new TtsSpeakPayload(
                                text == null ? "" : text,
                                0,
                                0L,
                                placement,
                                TtsTextInputMode.DOCUMENT,
                                TtsVoiceOptions.defaults()
                        )
                )
                .priority(priority == null ? Priority.HIGH : priority)
                .build());
    }
}

