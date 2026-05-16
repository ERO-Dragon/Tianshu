package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public class VoiceNotificationService {
    private final ProtocolRuntime runtime;

    public VoiceNotificationService(ProtocolRuntime runtime) {
        this.runtime = runtime;
    }

    public void speakAlert(String text) {
        submitAlert(text, false);
    }

    public void speakAlertWithInterrupt(String text) {
        submitAlert(text, true);
    }

    private void submitAlert(String text, boolean interruptCurrent) {
        runtime.submit(EnvelopeBuilder.commandToCapability(
                        TtsProtocolAdapter.SOURCE_ID,
                        ProtocolCapabilities.TTS_ALERT,
                        PayloadType.TTS_TEXT,
                        new TtsSpeakPayload(text, 0, 0L, interruptCurrent, "alert")
                )
                .priority(interruptCurrent ? Priority.CRITICAL : Priority.HIGH)
                .build());
    }
}
