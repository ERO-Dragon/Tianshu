package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ir.core.IRBaseUtils;
import com.rheinmetal.tianshu.function.ir.input.IrInputText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class IrWakeWordEnhancer {
    private static final double MIN_SCORE = 0.72D;
    private static final double MIN_PINYIN_OVERLAP = 0.65D;
    private static final double AMBIGUITY_MARGIN = 0.08D;

    IrInputText enhance(IrInputText input, List<IrCompiledVoiceTrigger> index) {
        if (input == null || input.blank() || index == null || index.isEmpty()) {
            return input;
        }
        List<Candidate> candidates = collectCandidates(input.text(), index);
        if (candidates.isEmpty()) {
            return input;
        }
        List<Candidate> selected = selectCandidates(candidates);
        if (selected.isEmpty()) {
            return input;
        }
        String repaired = apply(input.text(), selected);
        if (repaired.equals(input.text())) {
            return input;
        }
        return new IrInputText(repaired, input.rawText(), input.turnId(), input.sessionId(), input.source(), input.createdAt());
    }

    private List<Candidate> collectCandidates(String text, List<IrCompiledVoiceTrigger> index) {
        List<Candidate> candidates = new ArrayList<>();
        for (IrCompiledVoiceTrigger trigger : index) {
            if (trigger == null || trigger.wakeWords().isEmpty()) {
                continue;
            }
            for (IrCompiledVoiceWord word : trigger.wakeWords()) {
                Candidate candidate = bestCandidate(text, word);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    private Candidate bestCandidate(String text, IrCompiledVoiceWord word) {
        if (text == null || text.isBlank() || word == null || word.isEmpty()) {
            return null;
        }
        int targetLength = word.rawText().length();
        int minLength = Math.max(1, targetLength - 1);
        int maxLength = Math.min(text.length(), targetLength + 1);
        Candidate best = null;
        for (int length = minLength; length <= maxLength; length++) {
            for (int start = 0; start <= text.length() - length; start++) {
                int end = start + length;
                String slice = text.substring(start, end);
                Candidate candidate = score(slice, word, start, end);
                if (candidate != null && (best == null || candidate.score() > best.score())) {
                    best = candidate;
                }
            }
        }
        if (best == null || best.score() < thresholdFor(word) || best.pinyinOverlap() < MIN_PINYIN_OVERLAP) {
            return null;
        }
        return best;
    }

    private Candidate score(String slice, IrCompiledVoiceWord word, int start, int end) {
        String actual = IRBaseUtils.joinTokens(IRBaseUtils.tokenize(slice));
        String expected = word.normalizedText();
        if (actual.isBlank() || expected.isBlank()) {
            return null;
        }
        double lcs = lcsRatio(actual, expected);
        double overlap = overlapRatio(actual, expected);
        double score = lcs * 0.6D + overlap * 0.4D;
        return new Candidate(start, end, word.rawText(), score, overlap);
    }

    private double thresholdFor(IrCompiledVoiceWord word) {
        int length = word == null ? 0 : word.rawText().length();
        if (length <= 2) {
            return 0.82D;
        }
        if (length <= 4) {
            return 0.76D;
        }
        return MIN_SCORE;
    }

    private List<Candidate> selectCandidates(List<Candidate> candidates) {
        List<Candidate> sorted = candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::replacementLength).reversed()
                        .thenComparing(Comparator.comparingInt(Candidate::spanLength).reversed())
                        .thenComparing(Comparator.comparingDouble(Candidate::score).reversed())
                        .thenComparingInt(Candidate::start)
                        .thenComparing(Candidate::replacement))
                .toList();
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : sorted) {
            if (ambiguous(candidate, sorted) || overlapsAny(candidate, selected)) {
                continue;
            }
            selected.add(candidate);
        }
        selected.sort(Comparator.comparingInt(Candidate::start));
        return List.copyOf(selected);
    }

    private boolean ambiguous(Candidate candidate, List<Candidate> candidates) {
        for (Candidate other : candidates) {
            if (candidate == other || !candidate.overlaps(other) || candidate.replacement().equals(other.replacement())) {
                continue;
            }
            if (candidate.contains(other) || other.contains(candidate) || candidate.replacementContains(other)) {
                continue;
            }
            if (Math.abs(candidate.score() - other.score()) < AMBIGUITY_MARGIN) {
                return true;
            }
        }
        return false;
    }

    private boolean overlapsAny(Candidate candidate, List<Candidate> selected) {
        for (Candidate other : selected) {
            if (candidate.overlaps(other)) {
                return true;
            }
        }
        return false;
    }

    private String apply(String text, List<Candidate> selected) {
        StringBuilder builder = new StringBuilder(text);
        int offset = 0;
        for (Candidate candidate : selected) {
            int start = candidate.start() + offset;
            int end = candidate.end() + offset;
            builder.replace(start, end, candidate.replacement());
            offset += candidate.replacement().length() - (candidate.end() - candidate.start());
        }
        return builder.toString();
    }

    private static double lcsRatio(String left, String right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0D;
        }
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int i = 1; i <= left.length(); i++) {
            char leftChar = left.charAt(i - 1);
            for (int j = 1; j <= right.length(); j++) {
                if (leftChar == right.charAt(j - 1)) {
                    current[j] = previous[j - 1] + 1;
                } else {
                    current[j] = Math.max(previous[j], current[j - 1]);
                }
            }
            int[] tmp = previous;
            previous = current;
            current = tmp;
            java.util.Arrays.fill(current, 0);
        }
        return previous[right.length()] / (double) Math.max(left.length(), right.length());
    }

    private static double overlapRatio(String left, String right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0D;
        }
        int[] freq = new int[256];
        for (int i = 0; i < left.length(); i++) {
            char c = left.charAt(i);
            if (c < freq.length) {
                freq[c]++;
            }
        }
        int overlap = 0;
        for (int i = 0; i < right.length(); i++) {
            char c = right.charAt(i);
            if (c < freq.length && freq[c] > 0) {
                freq[c]--;
                overlap++;
            }
        }
        return overlap / (double) Math.max(left.length(), right.length());
    }

    private record Candidate(int start, int end, String replacement, double score, double pinyinOverlap) {
        private Candidate {
            replacement = replacement == null ? "" : replacement.trim();
        }

        private boolean overlaps(Candidate other) {
            return other != null && start < other.end && other.start < end;
        }

        private boolean contains(Candidate other) {
            return other != null && start <= other.start && end >= other.end;
        }

        private boolean replacementContains(Candidate other) {
            return other != null && (replacement.contains(other.replacement) || other.replacement.contains(replacement));
        }

        private int spanLength() {
            return end - start;
        }

        private int replacementLength() {
            return replacement.length();
        }
    }
}
