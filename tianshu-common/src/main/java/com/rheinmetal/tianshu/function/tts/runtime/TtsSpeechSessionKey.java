package com.rheinmetal.tianshu.function.tts.runtime;

public record TtsSpeechSessionKey(
        String sourceId,
        long sessionId,
        int turnId,
        String localId
) {
    public TtsSpeechSessionKey {
        sourceId = requireText(sourceId, "sourceId");
        sessionId = Math.max(0L, sessionId);
        localId = sessionId > 0L ? "" : requireText(localId, "localId");
    }

    public static TtsSpeechSessionKey of(String sourceId, long sessionId, int turnId, String envelopeId) {
        return new TtsSpeechSessionKey(sourceId, sessionId, turnId, envelopeId);
    }

    public String value() {
        return sessionId > 0L
                ? sourceId + ":" + sessionId + ":" + turnId
                : sourceId + ":" + localId;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
