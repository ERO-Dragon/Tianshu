package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record TtsPlaybackStatusPayload(
        TtsPlaybackState state,
        long occurredAtMillis
) implements ITianshuPayload {
    public TtsPlaybackStatusPayload {
        state = state == null ? TtsPlaybackState.IDLE : state;
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }

    public static TtsPlaybackStatusPayload now(TtsPlaybackState state) {
        return new TtsPlaybackStatusPayload(state, System.currentTimeMillis());
    }
}
