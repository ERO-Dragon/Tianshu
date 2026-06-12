package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsBackendType;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsPlaybackBufferEstimate;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMetrics;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMode;

final class TtsAdaptiveSynthesisPolicy {
    private static final long FULL_MODE_SAFETY_MILLIS = 350L;
    private static final double DEFAULT_FULL_RTF = 0.35D;
    private static final double DEFAULT_STREAMING_RTF = 0.75D;
    private static final long DEFAULT_FIRST_AUDIO_MILLIS = 450L;
    private static final long DEFAULT_AUDIO_MILLIS_PER_CHAR = 180L;
    private static final double EWMA_ALPHA = 0.25D;

    private final RollingEstimate fullEstimate = new RollingEstimate(DEFAULT_FULL_RTF);
    private final RollingEstimate streamingEstimate = new RollingEstimate(DEFAULT_STREAMING_RTF);
    private double firstAudioMillis = DEFAULT_FIRST_AUDIO_MILLIS;
    private double audioMillisPerChar = DEFAULT_AUDIO_MILLIS_PER_CHAR;

    synchronized TtsSynthesisMode decide(TtsBackendSnapshot backend, TtsRequest request, TtsPlaybackBufferEstimate buffer) {
        if (backend == null || backend.backendType() != TtsBackendType.MOSS || !backend.autoregressive()) {
            return TtsSynthesisMode.FULL;
        }
        long remaining = buffer == null ? 0L : buffer.remainingAudioMillis();
        long predictedAudioMillis = predictAudioMillis(request == null ? "" : request.text());
        long predictedFullMillis = Math.round(predictedAudioMillis * fullEstimate.value());
        long predictedStreamingMillis = Math.round(predictedAudioMillis * streamingEstimate.value());

        if (remaining >= predictedFullMillis + FULL_MODE_SAFETY_MILLIS) {
            return TtsSynthesisMode.FULL;
        }
        long fullAudibleGapMillis = Math.max(0L, predictedFullMillis - remaining);
        long streamingFirstAudioGapMillis = Math.max(0L, Math.round(firstAudioMillis) - remaining);
        long streamingCatchupGapMillis = Math.max(0L, predictedStreamingMillis - predictedAudioMillis - remaining);
        long streamingAudibleGapMillis = Math.max(streamingFirstAudioGapMillis, streamingCatchupGapMillis);
        return fullAudibleGapMillis <= streamingAudibleGapMillis ? TtsSynthesisMode.FULL : TtsSynthesisMode.STREAMING;
    }

    synchronized void record(TtsSynthesisMetrics metrics) {
        if (metrics == null || metrics.audioMillis() <= 0L || metrics.synthesisMillis() <= 0L) {
            return;
        }
        if (metrics.mode() == TtsSynthesisMode.STREAMING) {
            streamingEstimate.record(metrics.rtf());
            if (metrics.firstAudioMillis() > 0L) {
                firstAudioMillis = ewma(firstAudioMillis, metrics.firstAudioMillis());
            }
        } else {
            fullEstimate.record(metrics.rtf());
        }
        long observedAudioMillisPerChar = metrics.audioMillisPerCharacter();
        if (observedAudioMillisPerChar > 0L) {
            audioMillisPerChar = ewma(audioMillisPerChar, observedAudioMillisPerChar);
        }
    }

    private long predictAudioMillis(String text) {
        int length = text == null ? 0 : text.codePointCount(0, text.length());
        return Math.max(400L, Math.round(Math.max(1, length) * audioMillisPerChar));
    }

    private static double ewma(double previous, double observed) {
        return previous * (1.0D - EWMA_ALPHA) + observed * EWMA_ALPHA;
    }

    private static final class RollingEstimate {
        private double value;

        private RollingEstimate(double initialValue) {
            this.value = initialValue;
        }

        private double value() {
            return value;
        }

        private void record(double observed) {
            if (observed <= 0.0D || Double.isNaN(observed) || Double.isInfinite(observed)) {
                return;
            }
            value = ewma(value, observed);
        }
    }
}
