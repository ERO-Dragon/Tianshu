package com.rheinmetal.tianshu.function.tts.synthesis.moss;

@FunctionalInterface
interface MossStreamingDecodeCadence {
    int nextFrameCount(long emittedAudioSamples, int sampleRate, long firstAudioEmittedNanos, long nowNanos);

    static MossStreamingDecodeCadence fixed(int frameCount) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be positive");
        }
        return (emittedAudioSamples, sampleRate, firstAudioEmittedNanos, nowNanos) -> frameCount;
    }

    static MossStreamingDecodeCadence upstreamAdaptive() {
        return MossStreamingDecodeCadence::upstreamFrameCount;
    }

    private static int upstreamFrameCount(
            long emittedAudioSamples,
            int sampleRate,
            long firstAudioEmittedNanos,
            long nowNanos
    ) {
        if (firstAudioEmittedNanos < 0L || sampleRate <= 0) {
            return 1;
        }
        long elapsedNanos = Math.max(0L, nowNanos - firstAudioEmittedNanos);
        double leadSeconds = (Math.max(0L, emittedAudioSamples) / (double) sampleRate)
                - (elapsedNanos / 1_000_000_000.0d);
        if (leadSeconds < 0.20d) {
            return 1;
        }
        if (leadSeconds < 0.55d) {
            return 2;
        }
        if (leadSeconds < 1.10d) {
            return 4;
        }
        return 8;
    }
}
