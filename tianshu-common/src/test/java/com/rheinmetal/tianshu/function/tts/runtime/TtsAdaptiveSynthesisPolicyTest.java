package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsBackendType;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsPlaybackBufferEstimate;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMetrics;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TtsAdaptiveSynthesisPolicyTest {
    @Test
    void nonMossBackendKeepsSingleSentenceFullSynthesis() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();

        TtsSynthesisDecision decision = policy.planPlayback(
                snapshot(TtsBackendType.SHERPA, false),
                List.of("第一句。", "第二句。"),
                1,
                TtsPlaybackBufferEstimate.empty(),
                false
        );

        assertEquals(1, decision.sentenceCount());
        assertEquals("第一句。", decision.text());
        assertEquals(TtsSynthesisMode.FULL, decision.mode());
    }

    @Test
    void firstPlaybackBatchStartsImmediatelyAsOneStreamingSentence() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();

        TtsSynthesisDecision decision = policy.planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("第一句。", "第二句。"),
                2,
                TtsPlaybackBufferEstimate.empty(),
                true
        );

        assertEquals(1, decision.sentenceCount());
        assertEquals(TtsSynthesisMode.STREAMING, decision.mode());
    }

    @Test
    void sufficientBufferSelectsLargestValidGroupInFullMode() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();

        TtsSynthesisDecision decision = policy.planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("甲乙。", "丙丁。", "戊己。"),
                3,
                new TtsPlaybackBufferEstimate(2_000L, 3_000L, 1_000L),
                false
        );

        assertEquals(3, decision.sentenceCount());
        assertEquals("甲乙。丙丁。戊己。", decision.text());
        assertEquals(TtsSynthesisMode.FULL, decision.mode());
    }

    @Test
    void tightBufferStillKeepsMultipleCompleteSentencesInStreamingMode() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();

        TtsSynthesisDecision decision = policy.planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("甲乙。", "丙丁。", "戊己。"),
                3,
                new TtsPlaybackBufferEstimate(800L, 1_500L, 700L),
                false
        );

        assertEquals(3, decision.sentenceCount());
        assertEquals(TtsSynthesisMode.STREAMING, decision.mode());
    }

    @Test
    void shrinksGroupOnlyWhenPredictedFirstAudioMissesDeadline() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();

        TtsSynthesisDecision decision = policy.planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("第一句内容。", "第二句内容。", "第三句内容。"),
                3,
                new TtsPlaybackBufferEstimate(450L, 1_000L, 550L),
                false
        );

        assertEquals(2, decision.sentenceCount());
        assertEquals("第一句内容。第二句内容。", decision.text());
        assertEquals(TtsSynthesisMode.STREAMING, decision.mode());
    }

    @Test
    void backendTokenCeilingCapsSelectedSentenceCount() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();

        TtsSynthesisDecision decision = policy.planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("甲乙。", "丙丁。", "戊己。"),
                2,
                new TtsPlaybackBufferEstimate(5_000L, 5_000L, 0L),
                false
        );

        assertEquals(2, decision.sentenceCount());
    }

    @Test
    void pureSynthesisUsesLargestTokenSafeGroupWithoutPlaybackHeuristics() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();

        TtsSynthesisDecision decision = policy.planSynthesis(
                List.of("甲乙。", "丙丁。", "戊己。"),
                3
        );

        assertEquals(3, decision.sentenceCount());
        assertEquals(TtsSynthesisMode.FULL, decision.mode());
    }

    @Test
    void observedFirstAudioLatencyInfluencesLaterGroupSize() {
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy();
        policy.record(new TtsSynthesisMetrics(TtsSynthesisMode.STREAMING, 8, 1_500L, 1_000L, 850L));

        TtsSynthesisDecision decision = policy.planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("第一句。", "第二句。", "第三句。"),
                3,
                new TtsPlaybackBufferEstimate(500L, 1_000L, 500L),
                false
        );

        assertEquals(1, decision.sentenceCount());
        assertEquals(TtsSynthesisMode.STREAMING, decision.mode());
    }

    @Test
    void streamingEnabledKeepsStreamingOutletsForAutoregressiveBackends() {
        assertEquals(
                TtsSynthesisMode.STREAMING,
                streamingEnabledPolicy().planPlayback(
                        snapshot(TtsBackendType.MOSS, true),
                        List.of("第一句。", "第二句。"),
                        2,
                        TtsPlaybackBufferEstimate.empty(),
                        true
                ).mode()
        );
        assertEquals(
                TtsSynthesisMode.STREAMING,
                streamingEnabledPolicy().planPlayback(
                        snapshot(TtsBackendType.MOSS, true),
                        List.of("第一句。", "第二句。"),
                        2,
                        TtsPlaybackBufferEstimate.empty(),
                        false
                ).mode()
        );
        TtsSynthesisDecision tightBuffer = streamingEnabledPolicy().planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("第一句内容。", "第二句内容。", "第三句内容。"),
                3,
                new TtsPlaybackBufferEstimate(450L, 1_000L, 550L),
                false
        );
        assertEquals(2, tightBuffer.sentenceCount());
        assertEquals(TtsSynthesisMode.STREAMING, tightBuffer.mode());
    }

    @Test
    void streamingDisabledKeepsSingleFullSentenceOnFirstBatch() {
        TtsSynthesisDecision decision = streamingDisabledPolicy().planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("第一句。", "第二句。"),
                2,
                TtsPlaybackBufferEstimate.empty(),
                true
        );

        assertEquals(1, decision.sentenceCount());
        assertEquals("第一句。", decision.text());
        assertEquals(TtsSynthesisMode.FULL, decision.mode());
    }

    @Test
    void streamingDisabledKeepsSingleFullSentenceWithoutBufferedAudio() {
        TtsSynthesisDecision decision = streamingDisabledPolicy().planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("第一句。", "第二句。"),
                2,
                TtsPlaybackBufferEstimate.empty(),
                false
        );

        assertEquals(1, decision.sentenceCount());
        assertEquals("第一句。", decision.text());
        assertEquals(TtsSynthesisMode.FULL, decision.mode());
    }

    @Test
    void streamingDisabledNeverMergesSentencesEvenWithGenerousBuffer() {
        TtsSynthesisDecision decision = streamingDisabledPolicy().planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("甲乙。", "丙丁。", "戊己。"),
                3,
                new TtsPlaybackBufferEstimate(5_000L, 5_000L, 0L),
                false
        );

        assertEquals(1, decision.sentenceCount());
        assertEquals("甲乙。", decision.text());
        assertEquals(TtsSynthesisMode.FULL, decision.mode());
    }

    @Test
    void streamingDisabledKeepsSingleFullSentenceWhenTextExceedsBuffer() {
        TtsSynthesisDecision decision = streamingDisabledPolicy().planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("第一句内容。", "第二句内容。", "第三句内容。"),
                3,
                new TtsPlaybackBufferEstimate(450L, 1_000L, 550L),
                false
        );

        assertEquals(1, decision.sentenceCount());
        assertEquals("第一句内容。", decision.text());
        assertEquals(TtsSynthesisMode.FULL, decision.mode());
    }

    @Test
    void streamingDisabledLeavesNonAutoregressiveBackendsOnSingleFullSentence() {
        TtsSynthesisDecision decision = streamingDisabledPolicy().planPlayback(
                snapshot(TtsBackendType.SHERPA, false),
                List.of("第一句。", "第二句。"),
                2,
                new TtsPlaybackBufferEstimate(5_000L, 5_000L, 0L),
                true
        );

        assertEquals(1, decision.sentenceCount());
        assertEquals("第一句。", decision.text());
        assertEquals(TtsSynthesisMode.FULL, decision.mode());
    }

    @Test
    void streamingDisabledKeepsEmptyInputAsEmptyDecision() {
        TtsSynthesisDecision playback = streamingDisabledPolicy().planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of(),
                3,
                new TtsPlaybackBufferEstimate(5_000L, 5_000L, 0L),
                true
        );
        TtsSynthesisDecision synthesis = streamingDisabledPolicy().planSynthesis(List.of(), 3);

        assertEquals(0, playback.sentenceCount());
        assertEquals("", playback.text());
        assertEquals(TtsSynthesisMode.FULL, playback.mode());
        assertEquals(0, synthesis.sentenceCount());
        assertEquals(TtsSynthesisMode.FULL, synthesis.mode());
    }

    @Test
    void streamingDisabledKeepsPureSynthesisOnSingleSentence() {
        TtsSynthesisDecision decision = streamingDisabledPolicy().planSynthesis(
                List.of("甲乙。", "丙丁。", "戊己。"),
                3
        );

        assertEquals(1, decision.sentenceCount());
        assertEquals("甲乙。", decision.text());
        assertEquals(TtsSynthesisMode.FULL, decision.mode());
    }

    @Test
    void streamingToggleIsReadOnEveryPlan() {
        AtomicBoolean streamingEnabled = new AtomicBoolean(true);
        TtsAdaptiveSynthesisPolicy policy = new TtsAdaptiveSynthesisPolicy(streamingEnabled::get);

        TtsSynthesisDecision streaming = policy.planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("第一句。", "第二句。"),
                2,
                TtsPlaybackBufferEstimate.empty(),
                true
        );
        streamingEnabled.set(false);
        TtsSynthesisDecision full = policy.planPlayback(
                snapshot(TtsBackendType.MOSS, true),
                List.of("第一句。", "第二句。"),
                2,
                TtsPlaybackBufferEstimate.empty(),
                true
        );

        assertEquals(TtsSynthesisMode.STREAMING, streaming.mode());
        assertEquals(1, streaming.sentenceCount());
        assertEquals(TtsSynthesisMode.FULL, full.mode());
        assertEquals(1, full.sentenceCount());
    }

    private static TtsAdaptiveSynthesisPolicy streamingEnabledPolicy() {
        return new TtsAdaptiveSynthesisPolicy(() -> true);
    }

    private static TtsAdaptiveSynthesisPolicy streamingDisabledPolicy() {
        return new TtsAdaptiveSynthesisPolicy(() -> false);
    }

    private static TtsBackendSnapshot snapshot(TtsBackendType type, boolean autoregressive) {
        return new TtsBackendSnapshot(true, true, type, type.name().toLowerCase(), autoregressive, 24_000, ".", System.currentTimeMillis());
    }
}
