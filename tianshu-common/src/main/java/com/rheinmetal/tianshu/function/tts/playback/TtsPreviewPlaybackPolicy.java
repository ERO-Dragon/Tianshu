package com.rheinmetal.tianshu.function.tts.playback;

import com.rheinmetal.tianshu.function.tts.runtime.TtsRequestSource;

public record TtsPreviewPlaybackPolicy(
        long prebufferTargetMillis,
        long maxQueueMillis,
        int sampleRate
) {
    public TtsPreviewPlaybackPolicy {
        prebufferTargetMillis = Math.max(0L, prebufferTargetMillis);
        maxQueueMillis = Math.max(prebufferTargetMillis, maxQueueMillis);
        sampleRate = Math.max(1, sampleRate);
    }

    public static TtsPreviewPlaybackPolicy defaults(int sampleRate) {
        return new TtsPreviewPlaybackPolicy(120L, 800L, sampleRate);
    }

    public boolean appliesTo(TtsRequestSource source) {
        return TtsRequestSource.PREVIEW.equals(source);
    }

    public boolean shouldStart(long bufferedMillis, boolean finished) {
        return finished || bufferedMillis >= prebufferTargetMillis;
    }

    public long bytesToMillis(int byteLength) {
        if (byteLength <= 0) {
            return 0L;
        }
        return (long) byteLength * 1_000L / (sampleRate * 2L);
    }
}
