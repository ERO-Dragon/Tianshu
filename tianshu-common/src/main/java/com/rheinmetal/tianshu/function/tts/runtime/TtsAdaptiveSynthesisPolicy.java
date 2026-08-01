package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsBackendType;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsPlaybackBufferEstimate;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMetrics;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMode;

import java.util.List;

final class TtsAdaptiveSynthesisPolicy {
    private static final long FULL_MODE_SAFETY_MILLIS = 350L;
    private static final long STREAMING_START_TOLERANCE_MILLIS = 60L;
    private static final double DEFAULT_FULL_RTF = 0.35D;
    private static final double DEFAULT_STREAMING_RTF = 0.75D;
    private static final long DEFAULT_FIRST_AUDIO_BASE_MILLIS = 250L;
    private static final double DEFAULT_FIRST_AUDIO_MILLIS_PER_CHAR = 15.0D;
    private static final long DEFAULT_AUDIO_MILLIS_PER_CHAR = 180L;
    private static final double EWMA_ALPHA = 0.25D;
    private static final double FIRST_AUDIO_EWMA_ALPHA = 0.50D;
    private static final double MAX_RTF_OBSERVATION = 1.50D;

    private final RollingEstimate fullEstimate = new RollingEstimate(DEFAULT_FULL_RTF);
    private final RollingEstimate streamingEstimate = new RollingEstimate(DEFAULT_STREAMING_RTF);
    private double firstAudioMillisPerChar = DEFAULT_FIRST_AUDIO_MILLIS_PER_CHAR;
    private double audioMillisPerChar = DEFAULT_AUDIO_MILLIS_PER_CHAR;

    synchronized TtsSynthesisDecision planPlayback(
            TtsBackendSnapshot backend,
            List<String> availableSentences,
            int contextualSentenceLimit,
            TtsPlaybackBufferEstimate buffer,
            boolean firstBatch
    ) {
        int maxSentenceCount = availableSentenceCount(availableSentences, contextualSentenceLimit);
        if (maxSentenceCount == 0) {
            return new TtsSynthesisDecision(0, "", TtsSynthesisMode.FULL);
        }
        if (!isMossAutoregressive(backend)) {
            return decision(availableSentences, 1, TtsSynthesisMode.FULL);
        }
        if (firstBatch) {
            return decision(availableSentences, 1, TtsSynthesisMode.STREAMING);
        }

        long remainingAudioMillis = buffer == null ? 0L : buffer.remainingAudioMillis();
        if (remainingAudioMillis <= 0L) {
            return decision(availableSentences, 1, TtsSynthesisMode.STREAMING);
        }

        String largestText = combine(availableSentences, maxSentenceCount);
        if (fullFits(largestText, remainingAudioMillis)) {
            return new TtsSynthesisDecision(maxSentenceCount, largestText, TtsSynthesisMode.FULL);
        }

        for (int sentenceCount = maxSentenceCount; sentenceCount >= 1; sentenceCount--) {
            String text = combine(availableSentences, sentenceCount);
            if (sentenceCount < maxSentenceCount && fullFits(text, remainingAudioMillis)) {
                return new TtsSynthesisDecision(sentenceCount, text, TtsSynthesisMode.FULL);
            }
            if (streamingFits(text, remainingAudioMillis)) {
                return new TtsSynthesisDecision(sentenceCount, text, TtsSynthesisMode.STREAMING);
            }
        }
        return decision(availableSentences, 1, TtsSynthesisMode.STREAMING);
    }

    synchronized TtsSynthesisDecision planSynthesis(
            List<String> availableSentences,
            int contextualSentenceLimit
    ) {
        int sentenceCount = availableSentenceCount(availableSentences, contextualSentenceLimit);
        return sentenceCount == 0
                ? new TtsSynthesisDecision(0, "", TtsSynthesisMode.FULL)
                : decision(availableSentences, sentenceCount, TtsSynthesisMode.FULL);
    }

    synchronized void record(TtsSynthesisMetrics metrics) {
        if (metrics == null || metrics.audioMillis() <= 0L || metrics.synthesisMillis() <= 0L) {
            return;
        }
        if (metrics.mode() == TtsSynthesisMode.STREAMING) {
            streamingEstimate.record(metrics.rtf());
            if (metrics.firstAudioMillis() > 0L && metrics.textLength() > 0) {
                double observedPerChar = Math.max(
                        0.0D,
                        (metrics.firstAudioMillis() - DEFAULT_FIRST_AUDIO_BASE_MILLIS)
                                / (double) metrics.textLength()
                );
                firstAudioMillisPerChar = ewma(
                        firstAudioMillisPerChar,
                        observedPerChar,
                        FIRST_AUDIO_EWMA_ALPHA
                );
            }
        } else {
            fullEstimate.record(metrics.rtf());
        }
        long observedAudioMillisPerChar = metrics.audioMillisPerCharacter();
        if (observedAudioMillisPerChar > 0L) {
            audioMillisPerChar = ewma(audioMillisPerChar, observedAudioMillisPerChar, EWMA_ALPHA);
        }
    }

    private boolean fullFits(String text, long remainingAudioMillis) {
        long predictedFullMillis = Math.round(predictAudioMillis(text) * fullEstimate.value());
        return remainingAudioMillis >= predictedFullMillis + FULL_MODE_SAFETY_MILLIS;
    }

    private boolean streamingFits(String text, long remainingAudioMillis) {
        long predictedAudioMillis = predictAudioMillis(text);
        long predictedFirstAudioMillis = predictFirstAudioMillis(text);
        long predictedSynthesisMillis = Math.round(predictedAudioMillis * streamingEstimate.value());
        return predictedFirstAudioMillis <= remainingAudioMillis + STREAMING_START_TOLERANCE_MILLIS
                && predictedSynthesisMillis <= remainingAudioMillis + predictedAudioMillis;
    }

    private long predictAudioMillis(String text) {
        return Math.max(400L, Math.round(Math.max(1, textLength(text)) * audioMillisPerChar));
    }

    private long predictFirstAudioMillis(String text) {
        return Math.max(
                150L,
                Math.round(DEFAULT_FIRST_AUDIO_BASE_MILLIS + Math.max(1, textLength(text)) * firstAudioMillisPerChar)
        );
    }

    private static int availableSentenceCount(List<String> sentences, int contextualSentenceLimit) {
        if (sentences == null || sentences.isEmpty()) {
            return 0;
        }
        return Math.min(sentences.size(), Math.max(1, contextualSentenceLimit));
    }

    private static TtsSynthesisDecision decision(
            List<String> sentences,
            int sentenceCount,
            TtsSynthesisMode mode
    ) {
        return new TtsSynthesisDecision(sentenceCount, combine(sentences, sentenceCount), mode);
    }

    private static String combine(List<String> sentences, int sentenceCount) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < sentenceCount && index < sentences.size(); index++) {
            String sentence = sentences.get(index);
            if (sentence != null) {
                text.append(sentence.trim());
            }
        }
        return text.toString();
    }

    private static boolean isMossAutoregressive(TtsBackendSnapshot backend) {
        return backend != null
                && backend.backendType() == TtsBackendType.MOSS
                && backend.autoregressive();
    }

    private static int textLength(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    private static double ewma(double previous, double observed, double alpha) {
        return previous * (1.0D - alpha) + observed * alpha;
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
            value = ewma(value, Math.min(observed, MAX_RTF_OBSERVATION), EWMA_ALPHA);
        }
    }
}
