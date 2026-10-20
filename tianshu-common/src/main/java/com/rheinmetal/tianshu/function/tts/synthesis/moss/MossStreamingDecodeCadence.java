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

    /**
     * Emits a short first batch so the first audio arrives quickly, then switches to large batches
     * for the rest of the utterance.
     * <p>
     * The codec decode step costs a fixed ~80 ms per invocation regardless of how many frames it
     * decodes, so per-chunk decoding at a small batch size spends most of its time on call
     * overhead: a 39-frame utterance costs ten invocations instead of three. The first batch stays
     * small because it alone determines first-audio latency.
     */
    static MossStreamingDecodeCadence firstChunkThenBatched(int firstChunkFrames, int steadyBatchFrames) {
        if (firstChunkFrames <= 0 || steadyBatchFrames <= 0) {
            throw new IllegalArgumentException("frame counts must be positive");
        }
        return (emittedAudioSamples, sampleRate, firstAudioEmittedNanos, nowNanos) ->
                emittedAudioSamples <= 0L ? firstChunkFrames : steadyBatchFrames;
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
