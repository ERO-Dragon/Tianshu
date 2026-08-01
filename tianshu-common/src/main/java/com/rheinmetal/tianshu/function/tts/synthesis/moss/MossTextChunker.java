package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

final class MossTextChunker {
    private static final String SEMANTIC_BOUNDARIES = "。！？!?.;；，,、：:";

    private final int maxTokens;
    private final ToIntFunction<String> tokenCounter;

    MossTextChunker(int maxTokens, ToIntFunction<String> tokenCounter) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        this.maxTokens = maxTokens;
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
    }

    List<String> split(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<String> tokenSafeUnits = new ArrayList<>();
        for (String unit : semanticUnits(normalized)) {
            splitOversizedUnit(unit, tokenSafeUnits);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : tokenSafeUnits) {
            String candidate = current + unit;
            if (!current.isEmpty() && tokenCount(candidate) > maxTokens) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(unit);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return List.copyOf(chunks);
    }

    int contextualSentenceLimit(List<String> sentences) {
        if (sentences == null || sentences.isEmpty()) {
            return 1;
        }
        StringBuilder combined = new StringBuilder();
        int accepted = 0;
        for (String sentence : sentences) {
            if (sentence == null || sentence.isBlank()) {
                continue;
            }
            String candidate = combined + sentence.trim();
            if (accepted > 0 && tokenCount(candidate) > maxTokens) {
                break;
            }
            combined.append(sentence.trim());
            accepted++;
        }
        return Math.max(1, accepted);
    }

    private void splitOversizedUnit(String unit, List<String> destination) {
        if (unit.isEmpty()) {
            return;
        }
        int offset = 0;
        while (offset < unit.length()) {
            String remaining = unit.substring(offset);
            if (tokenCount(remaining) <= maxTokens) {
                destination.add(remaining);
                return;
            }
            int end = longestTokenSafePrefix(unit, offset);
            if (end <= offset) {
                throw new TtsFailureException(
                        TtsFailureCode.INVALID_REQUEST,
                        "TTS_MOSS_TEXT_TOKEN_LIMIT_UNSPLITTABLE maxTokens=" + maxTokens
                );
            }
            destination.add(unit.substring(offset, end));
            offset = end;
        }
    }

    private int longestTokenSafePrefix(String text, int start) {
        int codePoints = text.codePointCount(start, text.length());
        int low = 1;
        int high = codePoints;
        int acceptedEnd = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int end = text.offsetByCodePoints(start, middle);
            if (tokenCount(text.substring(start, end)) <= maxTokens) {
                acceptedEnd = end;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return acceptedEnd;
    }

    private List<String> semanticUnits(String text) {
        List<String> units = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            if (SEMANTIC_BOUNDARIES.indexOf(text.charAt(index)) >= 0) {
                units.add(text.substring(start, index + 1));
                start = index + 1;
            }
        }
        if (start < text.length()) {
            units.add(text.substring(start));
        }
        return units;
    }

    private int tokenCount(String text) {
        return Math.max(0, tokenCounter.applyAsInt(text));
    }
}
