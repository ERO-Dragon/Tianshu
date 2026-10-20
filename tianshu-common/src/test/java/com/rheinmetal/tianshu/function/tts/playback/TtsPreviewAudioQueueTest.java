package com.rheinmetal.tianshu.function.tts.playback;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsPreviewAudioQueueTest {
    @Test
    void preservesFifoAndFinishesShortStreamBeforePrebufferTarget() throws Exception {
        TtsPreviewPlaybackPolicy policy = new TtsPreviewPlaybackPolicy(100L, 200L, 1_000);
        TtsPreviewAudioQueue queue = new TtsPreviewAudioQueue(policy);
        byte[] first = new byte[100];
        byte[] second = new byte[100];

        assertEquals(TtsPreviewAudioQueue.OfferResult.ACCEPTED, queue.offer(first, Duration.ofMillis(50), () -> false));
        assertEquals(50L, queue.bufferedMillis());
        queue.finish();
        assertArrayEquals(first, queue.poll().audio());
        assertEquals(TtsPreviewAudioQueue.PollResult.FINISHED, queue.poll().result());
        assertTrue(queue.canStart());
        assertEquals(TtsPreviewAudioQueue.OfferResult.REJECTED, queue.offer(second, Duration.ofMillis(1), () -> false));
    }

    @Test
    void blocksAtMaxDurationAndCancellationReleasesProducer() throws Exception {
        TtsPreviewAudioQueue queue = new TtsPreviewAudioQueue(new TtsPreviewPlaybackPolicy(100L, 100L, 1_000));
        queue.offer(new byte[200], Duration.ofMillis(50), () -> false);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<TtsPreviewAudioQueue.OfferResult> result = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            started.countDown();
            result.set(queue.offer(new byte[2], Duration.ofSeconds(2), () -> false));
        });
        producer.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread.sleep(50L);
        queue.cancel();
        producer.join(1_000L);
        assertEquals(TtsPreviewAudioQueue.OfferResult.CANCELLED, result.get());
    }
}
