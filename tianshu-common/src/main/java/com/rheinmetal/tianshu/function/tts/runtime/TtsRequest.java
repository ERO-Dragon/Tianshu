package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.protocol.Priority;

import java.util.UUID;

public record TtsRequest(
        String requestId,
        String groupId,
        String envelopeId,
        String traceId,
        String text,
        TtsRequestSource source,
        TtsPlaybackPolicy playbackPolicy,
        Priority priority,
        TtsVoiceProfile voiceProfile,
        boolean expectPlaybackEndEvent
) {
    public TtsRequest {
        requestId = normalize(requestId, UUID.randomUUID().toString());
        groupId = normalize(groupId, requestId);
        envelopeId = normalize(envelopeId, requestId);
        traceId = normalize(traceId, requestId);
        text = text == null ? "" : text.trim();
        source = source == null ? TtsRequestSource.UNKNOWN : source;
        playbackPolicy = playbackPolicy == null ? TtsPlaybackPolicy.QUEUE : playbackPolicy;
        priority = priority == null ? Priority.NORMAL : priority;
        voiceProfile = voiceProfile == null ? TtsVoiceProfile.defaults() : voiceProfile;
    }

    public boolean interruptive() {
        return playbackPolicy == TtsPlaybackPolicy.REPLACE_CURRENT
                || playbackPolicy == TtsPlaybackPolicy.CANCEL_SENTENCE_AND_PLAY
                || playbackPolicy == TtsPlaybackPolicy.CANCEL_SESSION_AND_PLAY;
    }

    private static String normalize(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }
}
