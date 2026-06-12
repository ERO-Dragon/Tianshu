package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsPlaybackBufferEstimate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsPlaybackBufferTrackerTest {
    @Test
    void estimatesSubmittedPcm16MonoPlaybackTime() {
        TtsPlaybackBufferTracker tracker = new TtsPlaybackBufferTracker();

        tracker.begin(24_000);
        tracker.recordPcm16Mono(new byte[24_000]);
        TtsPlaybackBufferEstimate estimate = tracker.estimate();

        assertEquals(500L, estimate.submittedAudioMillis());
        assertTrue(estimate.remainingAudioMillis() <= 500L);
        assertTrue(estimate.remainingAudioMillis() >= 0L);
    }

    @Test
    void clearResetsEstimate() {
        TtsPlaybackBufferTracker tracker = new TtsPlaybackBufferTracker();
        tracker.begin(24_000);
        tracker.recordPcm16Mono(new byte[24_000]);

        tracker.clear();

        assertEquals(TtsPlaybackBufferEstimate.empty(), tracker.estimate());
    }
}
