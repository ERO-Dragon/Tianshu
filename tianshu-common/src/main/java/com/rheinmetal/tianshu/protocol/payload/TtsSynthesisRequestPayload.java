package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record TtsSynthesisRequestPayload(
        String requestId,
        String text,
        boolean streaming,
        long ttlMillis,
        TtsVoiceOptions voice
) implements ITianshuPayload {
    public TtsSynthesisRequestPayload {
        requestId = requestId == null ? "" : requestId.trim();
        text = text == null ? "" : text.trim();
        ttlMillis = Math.max(1_000L, ttlMillis);
        voice = voice == null ? TtsVoiceOptions.defaults() : voice;
    }
}
