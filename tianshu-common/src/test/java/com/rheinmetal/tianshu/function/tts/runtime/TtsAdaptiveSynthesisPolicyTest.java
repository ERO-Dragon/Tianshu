package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsBackendType;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsPlaybackBufferEstimate;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMetrics;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMode;
import com.rheinmetal.tianshu.protocol.Priority;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TtsAdaptiveSynthesisPolicyTest {
    @Test
    void nonMossBackendUsesFullSynthesis() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();

        TtsSynthesisMode mode = policy.decide(
                snapshot(TtsBackendType.SHERPA, false),
                request("你好"),
                new TtsPlaybackBufferEstimate(0L, 0L, 0L)
        );

        assertEquals(TtsSynthesisMode.FULL, mode);
    }

    @Test
    void mossUsesStreamingWhenPlaybackBufferIsLow() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();

        TtsSynthesisMode mode = policy.decide(
                snapshot(TtsBackendType.MOSS, true),
                request("你好，我是天枢人工智能助手。"),
                new TtsPlaybackBufferEstimate(100L, 500L, 400L)
        );

        assertEquals(TtsSynthesisMode.STREAMING, mode);
    }

    @Test
    void mossCanUseFullSynthesisForShortTextEvenWithoutPlaybackBuffer() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();

        TtsSynthesisMode mode = policy.decide(
                snapshot(TtsBackendType.MOSS, true),
                request("你好。"),
                TtsPlaybackBufferEstimate.empty()
        );

        assertEquals(TtsSynthesisMode.FULL, mode);
    }

    @Test
    void mossUsesFullWhenPlaybackBufferCanCoverPredictedSynthesis() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();

        TtsSynthesisMode mode = policy.decide(
                snapshot(TtsBackendType.MOSS, true),
                request("你好。"),
                new TtsPlaybackBufferEstimate(2_000L, 3_000L, 1_000L)
        );

        assertEquals(TtsSynthesisMode.FULL, mode);
    }

    @Test
    void observedSlowFullSynthesisMakesPolicyPreferStreamingWithTightBuffer() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();
        policy.record(new TtsSynthesisMetrics(TtsSynthesisMode.FULL, 4, 1_000L, 3_000L, 3_000L));

        TtsSynthesisMode mode = policy.decide(
                snapshot(TtsBackendType.MOSS, true),
                request("你好你好你好你好你好。"),
                new TtsPlaybackBufferEstimate(900L, 1_500L, 600L)
        );

        assertEquals(TtsSynthesisMode.STREAMING, mode);
    }

    private static TtsBackendSnapshot snapshot(TtsBackendType type, boolean autoregressive) {
        return new TtsBackendSnapshot(true, true, type, type.name().toLowerCase(), autoregressive, 24_000, ".", System.currentTimeMillis());
    }

    private static TtsRequest request(String text) {
        return new TtsRequest("request", "request", "envelope", "trace", text, TtsRequestSource.AX, TtsPlaybackPolicy.QUEUE, Priority.NORMAL, TtsVoiceProfile.defaults(), false);
    }
}
