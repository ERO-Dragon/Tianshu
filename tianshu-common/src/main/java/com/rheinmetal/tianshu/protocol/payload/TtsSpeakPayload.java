package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record TtsSpeakPayload(
        String text,
        int turnId,
        long sessionId,
        TtsPlaybackPlacement placement,
        String voiceStyle
) implements ITianshuPayload {
    public TtsSpeakPayload {
        if (text == null) text = "";
        if (placement == null) placement = TtsPlaybackPlacement.QUEUE_AFTER_SESSION;
        if (voiceStyle == null) voiceStyle = "";
    }
}
