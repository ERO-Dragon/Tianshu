package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record TtsSynthesisRequestPayload(
        String requestId,
        String text,
        boolean streaming,
        long ttlMillis,
        String voiceStyle
) implements ITianshuPayload {
    public TtsSynthesisRequestPayload(String text, boolean streaming, String voiceStyle) {
        this("", text, streaming, 30_000L, voiceStyle);
    }

    public TtsSynthesisRequestPayload {
        requestId = requestId == null ? "" : requestId.trim();
        text = text == null ? "" : text.trim();
        ttlMillis = Math.max(1_000L, ttlMillis);
        voiceStyle = voiceStyle == null ? "" : voiceStyle.trim();
    }
}
