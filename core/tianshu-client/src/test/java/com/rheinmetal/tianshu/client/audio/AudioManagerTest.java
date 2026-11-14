package com.rheinmetal.tianshu.client.audio;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AudioManagerTest {
    @Test
    void gracefulFinishDeliversTheCompletionHookExactlyOnce() {
        AudioManager manager = new AudioManager();
        AtomicInteger delivered = new AtomicInteger();
        manager.setOnPlaybackFinished(delivered::incrementAndGet);

        manager.finishTtsPlayback("request-1");
        assertEquals(1, delivered.get(), "a graceful finish must report playback completion");

        manager.finishTtsPlayback("request-1");
        assertEquals(1, delivered.get(), "the hook is consumed by the first finish");
    }

    @Test
    void abortedPlaybackNeverReportsCompletion() {
        AudioManager manager = new AudioManager();
        AtomicInteger delivered = new AtomicInteger();
        manager.setOnPlaybackFinished(delivered::incrementAndGet);

        manager.stopTtsPlayback();
        manager.finishTtsPlayback("request-2");
        assertEquals(0, delivered.get(), "an aborted utterance must not report completion");
    }

    @Test
    void lineMetricsUseFullLifetimeAndExposeOnlyObservableWriteGap() {
        Map<String, String> metrics = AudioManager.ttsPlaybackMetricAttributes(
                " preview-1 ",
                1_000_000_000L,
                1_250_000_000L,
                2_000_000L,
                3_000_000L,
                2L,
                320L,
                17L,
                64
        );

        assertEquals("preview-1", metrics.get("requestId"));
        assertEquals("250", metrics.get("lineOpenMillis"));
        assertEquals("2", metrics.get("writeMillis"));
        assertEquals("3", metrics.get("drainMillis"));
        assertEquals("2", metrics.get("writeCount"));
        assertEquals("320", metrics.get("bytesWritten"));
        assertEquals("17", metrics.get("maxWriteGapMillis"));
        assertEquals("64", metrics.get("minAvailableBytes"));
        assertEquals("true", metrics.get("writeGapObserved"));
        assertFalse(metrics.containsKey("starvationProxy"));
    }

    @Test
    void lineMetricsClampInvalidDurationsAndAvailability() {
        Map<String, String> metrics = AudioManager.ttsPlaybackMetricAttributes(
                "request",
                2_000L,
                1_000L,
                -1L,
                -2L,
                -3L,
                -4L,
                -5L,
                -6
        );

        assertEquals("0", metrics.get("lineOpenMillis"));
        assertEquals("0", metrics.get("writeMillis"));
        assertEquals("0", metrics.get("drainMillis"));
        assertEquals("0", metrics.get("writeCount"));
        assertEquals("0", metrics.get("bytesWritten"));
        assertEquals("0", metrics.get("maxWriteGapMillis"));
        assertEquals("0", metrics.get("minAvailableBytes"));
        assertEquals("false", metrics.get("writeGapObserved"));
    }
}
