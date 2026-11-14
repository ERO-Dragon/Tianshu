package com.rheinmetal.tianshu.function.echo.synthesis.moss;

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

    /**
     * Experimental cadence used by the opt-in MOSS benchmark: a short first batch keeps first-audio
     * latency low, then the caller chooses a larger steady batch.
     */
    static MossStreamingDecodeCadence firstChunkThenBatched(int firstChunkFrames, int steadyBatchFrames) {
        if (firstChunkFrames <= 0 || steadyBatchFrames <= 0) {
            throw new IllegalArgumentException("frame counts must be positive");
        }
        return (emittedAudioSamples, sampleRate, firstAudioEmittedNanos, nowNanos) ->
                emittedAudioSamples <= 0L ? firstChunkFrames : steadyBatchFrames;
    }

    /**
     * Keeps the second decode batch as short as the first one, then grows to the steady size.
     *
     * <p>The first chunk only owns a few hundred milliseconds of audio; if the second chunk is
     * decoded at the steady (large) size, the consumer can run dry before it arrives. Two short
     * batches bridge that opening window without delaying the first audio.</p>
     */
    static MossStreamingDecodeCadence firstTwoChunksThenBatched(
            int firstChunkFrames,
            int secondChunkFrames,
            int steadyBatchFrames
    ) {
        if (firstChunkFrames <= 0 || secondChunkFrames <= 0 || steadyBatchFrames <= 0) {
            throw new IllegalArgumentException("frame counts must be positive");
        }
        return (emittedAudioSamples, sampleRate, firstAudioEmittedNanos, nowNanos) -> {
            if (emittedAudioSamples <= 0L) {
                return firstChunkFrames;
            }
            long firstChunkAudioSamples = Math.max(1, sampleRate) * (long) firstChunkFrames * 80L / 1000L;
            return emittedAudioSamples <= firstChunkAudioSamples ? secondChunkFrames : steadyBatchFrames;
        };
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
