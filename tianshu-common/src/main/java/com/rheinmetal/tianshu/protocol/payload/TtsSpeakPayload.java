package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record TtsSpeakPayload(
        String text,
        int turnId,
        long sessionId,
        TtsPlaybackPlacement placement,
        TtsTextInputMode inputMode,
        TtsVoiceOptions voice
) implements ITianshuPayload {
    public TtsSpeakPayload {
        text = text == null ? "" : text;
        placement = placement == null ? TtsPlaybackPlacement.QUEUE_AFTER_SESSION : placement;
        inputMode = inputMode == null ? TtsTextInputMode.DOCUMENT : inputMode;
        voice = voice == null ? TtsVoiceOptions.defaults() : voice;
    }
}
