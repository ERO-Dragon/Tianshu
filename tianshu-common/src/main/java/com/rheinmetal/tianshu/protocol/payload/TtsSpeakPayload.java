package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record TtsSpeakPayload(String text, int turnId, long sessionId, boolean interruptCurrent, String voiceStyle) implements ITianshuPayload {
    public TtsSpeakPayload {
        if (text == null) text = "";
        if (voiceStyle == null) voiceStyle = "";
    }
}
