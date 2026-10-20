package com.rheinmetal.tianshu.function.tts.playback;

import com.rheinmetal.tianshu.function.tts.runtime.TtsRequestSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsPreviewPlaybackPolicyTest {
    @Test
    void convertsPcmBytesToMillisUsingMonoPcm16() {
        TtsPreviewPlaybackPolicy policy = TtsPreviewPlaybackPolicy.defaults(24_000);

        assertEquals(1_000L, policy.bytesToMillis(24_000 * 2));
        assertTrue(policy.shouldStart(120L, false));
        assertFalse(policy.shouldStart(119L, false));
        assertTrue(policy.appliesTo(TtsRequestSource.PREVIEW));
        assertFalse(policy.appliesTo(TtsRequestSource.SYSTEM));
    }
}
