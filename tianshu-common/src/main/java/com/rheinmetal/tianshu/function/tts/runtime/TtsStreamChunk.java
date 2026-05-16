package com.rheinmetal.tianshu.function.tts.runtime;

public record TtsStreamChunk(
        String streamId,
        String envelopeId,
        String traceId,
        String text,
        TtsRequestSource source,
        TtsPlaybackPolicy playbackPolicy,
        TtsVoiceProfile voiceProfile,
        boolean last
) {
    public TtsStreamChunk {
        streamId = normalizeIdentity(streamId, envelopeId, traceId);
        envelopeId = envelopeId == null || envelopeId.isBlank() ? streamId : envelopeId.trim();
        traceId = traceId == null || traceId.isBlank() ? streamId : traceId.trim();
        text = text == null ? "" : text;
        source = source == null ? TtsRequestSource.ASSISTANT : source;
        playbackPolicy = playbackPolicy == null ? TtsPlaybackPolicy.QUEUE : playbackPolicy;
        voiceProfile = voiceProfile == null ? TtsVoiceProfile.defaults() : voiceProfile;
    }

    private static String normalizeIdentity(String streamId, String envelopeId, String traceId) {
        if (streamId != null && !streamId.isBlank()) {
            return streamId.trim();
        }
        if (envelopeId != null && !envelopeId.isBlank()) {
            return envelopeId.trim();
        }
        if (traceId != null && !traceId.isBlank()) {
            return traceId.trim();
        }
        return "tts-stream:" + System.nanoTime();
    }
}
