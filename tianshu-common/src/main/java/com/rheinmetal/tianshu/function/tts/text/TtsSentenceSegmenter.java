package com.rheinmetal.tianshu.function.tts.text;

import java.util.Locale;
import java.util.Set;

public final class TtsSentenceSegmenter {
    private static final int DEFAULT_MIN_LENGTH = 24;
    private static final int DEFAULT_PREFERRED_LENGTH = 90;
    private static final int DEFAULT_MAX_LENGTH = 180;
    private static final Set<String> LATIN_ABBREVIATIONS = Set.of(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "fig", "no", "vol"
    );

    private final int minLength;
    private final int preferredLength;
    private final int maxLength;

    public TtsSentenceSegmenter() {
        this(DEFAULT_MIN_LENGTH, DEFAULT_PREFERRED_LENGTH, DEFAULT_MAX_LENGTH);
    }

    public TtsSentenceSegmenter(int maxLength) {
        this(DEFAULT_MIN_LENGTH, Math.max(40, maxLength / 2), maxLength);
    }

    public TtsSentenceSegmenter(int minLength, int preferredLength, int maxLength) {
        this.minLength = Math.max(8, minLength);
        this.preferredLength = Math.max(this.minLength + 8, preferredLength);
        this.maxLength = Math.max(this.preferredLength + 16, maxLength);
    }

    public boolean shouldFlush(String text) {
        return nextBoundary(text).shouldFlush();
    }

    public SegmentBoundary nextBoundary(String text) {
        if (text == null || text.isBlank()) {
            return SegmentBoundary.none();
        }
        String value = text.stripLeading();
        if (value.isBlank()) {
            return SegmentBoundary.none();
        }
        int boundary = findBestBoundary(value);
        if (boundary > 0) {
            return new SegmentBoundary(true, boundary);
        }
        if (value.length() >= maxLength) {
            return new SegmentBoundary(true, findForcedBoundary(value));
        }
        return SegmentBoundary.none();
    }

    private int findBestBoundary(String text) {
        int preferredBoundary = -1;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (!isBoundaryCandidate(text, i, current)) {
                continue;
            }
            int end = consumeTrailingClosers(text, i + 1);
            if (end < minLength) {
                continue;
            }
            if (isStrongBoundary(current)) {
                return end;
            }
            preferredBoundary = end;
            if (end >= preferredLength) {
                return end;
            }
        }
        return text.length() >= preferredLength ? preferredBoundary : -1;
    }

    private int findForcedBoundary(String text) {
        int bestSoftBoundary = -1;
        int limit = Math.min(text.length(), maxLength);
        for (int i = minLength; i < limit; i++) {
            char current = text.charAt(i);
            if (isSoftBoundary(current)) {
                bestSoftBoundary = consumeTrailingClosers(text, i + 1);
            }
        }
        if (bestSoftBoundary > 0) {
            return bestSoftBoundary;
        }
        for (int i = limit - 1; i >= minLength; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }
        return limit;
    }

    private boolean isBoundaryCandidate(String text, int index, char current) {
        if (isStrongBoundary(current)) {
            return true;
        }
        if (isSoftBoundary(current)) {
            return true;
        }
        if (current == '.') {
            return isSafePeriodBoundary(text, index);
        }
        if (current == '\n') {
            return true;
        }
        return false;
    }

    private boolean isSafePeriodBoundary(String text, int index) {
        if (isDecimalPoint(text, index)) {
            return false;
        }
        String token = previousLatinToken(text, index).toLowerCase(Locale.ROOT);
        if (LATIN_ABBREVIATIONS.contains(token)) {
            return false;
        }
        if (token.length() == 1 && Character.isUpperCase(token.charAt(0))) {
            return false;
        }
        return true;
    }

    private String previousLatinToken(String text, int index) {
        int start = index - 1;
        while (start >= 0) {
            char value = text.charAt(start);
            if (!(Character.isLetter(value) || value == '.')) {
                break;
            }
            start--;
        }
        return text.substring(start + 1, index);
    }

    private boolean isDecimalPoint(String text, int index) {
        return index > 0
                && index + 1 < text.length()
                && Character.isDigit(text.charAt(index - 1))
                && Character.isDigit(text.charAt(index + 1));
    }

    private boolean isStrongBoundary(char value) {
        return value == '。' || value == '！' || value == '!' || value == '？' || value == '?';
    }

    private boolean isSoftBoundary(char value) {
        return value == '；' || value == ';' || value == '，' || value == ',' || value == '、' || value == '：' || value == ':' || value == '\n';
    }

    private int consumeTrailingClosers(String text, int index) {
        int result = index;
        while (result < text.length()) {
            char value = text.charAt(result);
            if (value == '”' || value == '’' || value == '"' || value == '\'' || value == ')' || value == '）' || value == ']' || value == '】') {
                result++;
                continue;
            }
            break;
        }
        return result;
    }

    public record SegmentBoundary(boolean shouldFlush, int endIndex) {
        public SegmentBoundary {
            if (!shouldFlush) {
                endIndex = -1;
            }
        }

        public static SegmentBoundary none() {
            return new SegmentBoundary(false, -1);
        }
    }
}
