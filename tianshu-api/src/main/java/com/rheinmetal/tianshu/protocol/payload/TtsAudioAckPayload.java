package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record TtsAudioAckPayload(String requestId) implements ITianshuPayload {
    public TtsAudioAckPayload {
        requestId = requestId == null ? "" : requestId.trim();
    }
}
