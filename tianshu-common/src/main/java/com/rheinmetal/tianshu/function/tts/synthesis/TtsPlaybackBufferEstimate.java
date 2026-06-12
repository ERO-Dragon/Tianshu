package com.rheinmetal.tianshu.function.tts.synthesis;

public record TtsPlaybackBufferEstimate(
        long remainingAudioMillis,
        long submittedAudioMillis,
        long elapsedPlaybackMillis
) {
    public TtsPlaybackBufferEstimate {
        remainingAudioMillis = Math.max(0L, remainingAudioMillis);
        submittedAudioMillis = Math.max(0L, submittedAudioMillis);
        elapsedPlaybackMillis = Math.max(0L, elapsedPlaybackMillis);
    }

    public static TtsPlaybackBufferEstimate empty() {
        return new TtsPlaybackBufferEstimate(0L, 0L, 0L);
    }
}
