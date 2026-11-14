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
    void everyCadenceBatchFitsInsidePreviewQueueCapacity() {
        TtsPreviewPlaybackPolicy policy = TtsPreviewPlaybackPolicy.defaults(PREVIEW_SAMPLE_RATE);

        assertFits(policy, "first", MossTtsService.FIRST_CHUNK_FRAMES);
        assertFits(policy, "second", MossTtsService.SECOND_CHUNK_FRAMES);
        assertFits(policy, "steady", MossTtsService.STEADY_CHUNK_FRAMES);
    }

    @Test
    void firstAndSecondChunkStayAtOrBelowSteadyChunk() {
        assertTrue(MossTtsService.FIRST_CHUNK_FRAMES <= MossTtsService.STEADY_CHUNK_FRAMES,
                "The first chunk must stay at or below the steady batch size to keep first-audio latency low");
        assertTrue(MossTtsService.SECOND_CHUNK_FRAMES <= MossTtsService.STEADY_CHUNK_FRAMES,
                "The second chunk must stay at or below the steady batch size to bridge the first one");
    }

    private static void assertFits(TtsPreviewPlaybackPolicy policy, String name, int frames) {
        long chunkMillis = (long) frames * AUDIO_MILLIS_PER_FRAME;
        assertTrue(chunkMillis <= policy.maxQueueMillis(),
                "The " + name + " MOSS chunk of " + chunkMillis + " ms exceeds the preview queue capacity of "
                        + policy.maxQueueMillis() + " ms; the queue would reject it and drop that audio");
    }
}
