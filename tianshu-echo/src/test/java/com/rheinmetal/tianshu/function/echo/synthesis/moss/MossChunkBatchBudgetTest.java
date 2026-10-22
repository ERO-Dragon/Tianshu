package com.rheinmetal.tianshu.function.echo.synthesis.moss;

import com.rheinmetal.tianshu.function.echo.playback.TtsPreviewPlaybackPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the boundary between MOSS streaming chunking and the preview playback queue.
 * <p>
 * A chunk whose own duration exceeds the queue capacity can never be accepted: the queue waits for
 * space that can never appear, then rejects the chunk and the audio is silently dropped. That
 * regression made TTS preview play only the first couple of words and then fall silent.
 */
class MossChunkBatchBudgetTest {
    /** One MOSS frame decodes to 80 ms of audio (39 frames == 3120 ms). */
    private static final int AUDIO_MILLIS_PER_FRAME = 80;
    private static final int PREVIEW_SAMPLE_RATE = 48_000;

    @Test
    void steadyChunkFitsInsidePreviewQueueCapacity() {
        TtsPreviewPlaybackPolicy policy = TtsPreviewPlaybackPolicy.defaults(PREVIEW_SAMPLE_RATE);
        long steadyChunkMillis = (long) MossTtsService.STEADY_CHUNK_FRAMES * AUDIO_MILLIS_PER_FRAME;

        assertTrue(steadyChunkMillis <= policy.maxQueueMillis(),
                "A " + steadyChunkMillis + " ms MOSS chunk exceeds the preview queue capacity of "
                        + policy.maxQueueMillis() + " ms; the queue would reject it and drop that audio");
    }

    @Test
    void firstChunkStaysAtOrBelowSteadyChunk() {
        assertTrue(MossTtsService.FIRST_CHUNK_FRAMES <= MossTtsService.STEADY_CHUNK_FRAMES,
                "The first chunk must stay at or below the steady batch size to keep first-audio latency low");
    }
}
