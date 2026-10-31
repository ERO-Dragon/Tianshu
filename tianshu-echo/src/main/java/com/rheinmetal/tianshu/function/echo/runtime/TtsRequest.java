package com.rheinmetal.tianshu.function.echo.runtime;

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
        TtsVoiceProfile voiceProfile
) {
    public TtsRequest {
        requestId = normalize(requestId, UUID.randomUUID().toString());
        groupId = normalize(groupId, requestId);
        envelopeId = normalize(envelopeId, requestId);
        traceId = normalize(traceId, requestId);
        text = text == null ? "" : text;
        source = source == null ? TtsRequestSource.UNKNOWN : source;
        playbackPolicy = playbackPolicy == null ? TtsPlaybackPolicy.QUEUE : playbackPolicy;
        priority = priority == null ? Priority.NORMAL : priority;
        voiceProfile = voiceProfile == null ? TtsVoiceProfile.defaults() : voiceProfile;
    }

    private static String normalize(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }
}
