package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record AsrSpeechActivityPayload(boolean speaking, long sessionId, long occurredAtMillis) implements ITianshuPayload {
    public AsrSpeechActivityPayload {
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }

    public static AsrSpeechActivityPayload speaking(long sessionId) {
        return new AsrSpeechActivityPayload(true, sessionId, System.currentTimeMillis());
    }

    public static AsrSpeechActivityPayload silent(long sessionId) {
        return new AsrSpeechActivityPayload(false, sessionId, System.currentTimeMillis());
    }
}
