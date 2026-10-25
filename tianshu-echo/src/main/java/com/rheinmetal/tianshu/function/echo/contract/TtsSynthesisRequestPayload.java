package com.rheinmetal.tianshu.function.echo.contract;

import com.rheinmetal.tianshu.protocol.payload.TtsVoiceOptions;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record TtsSynthesisRequestPayload(
        String requestId,
        String text,
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
