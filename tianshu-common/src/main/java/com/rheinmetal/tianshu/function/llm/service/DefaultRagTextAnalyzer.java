package com.rheinmetal.tianshu.function.llm.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DefaultRagTextAnalyzer implements RagTextAnalyzer {

    static final DefaultRagTextAnalyzer INSTANCE = new DefaultRagTextAnalyzer();

    private static final int MAX_HAN_NGRAM = 3;

    private DefaultRagTextAnalyzer() {
    }

    @Override
    public List<String> analyze(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> terms = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        List<String> hanRun = new ArrayList<>();
        int[] codePoints = text.toLowerCase(Locale.ROOT).codePoints().toArray();

        for (int codePoint : codePoints) {
            if (isAsciiLetterOrDigit(codePoint)) {
                flushHanRun(hanRun, terms);
                word.appendCodePoint(codePoint);
            } else if (isHan(codePoint)) {
                flushWord(word, terms);
                hanRun.add(new String(Character.toChars(codePoint)));
            } else if (Character.isLetterOrDigit(codePoint)) {
                flushHanRun(hanRun, terms);
                flushWord(word, terms);
                terms.add(new String(Character.toChars(codePoint)));
            } else {
                flushWord(word, terms);
                flushHanRun(hanRun, terms);
            }
        }

        flushWord(word, terms);
        flushHanRun(hanRun, terms);
        return terms;
    }

    private static void flushWord(StringBuilder word, List<String> terms) {
        if (!word.isEmpty()) {
            terms.add(word.toString());
            word.setLength(0);
        }
    }

    private static void flushHanRun(List<String> hanRun, List<String> terms) {
        if (hanRun.isEmpty()) {
            return;
        }
        for (int size = 1; size <= MAX_HAN_NGRAM && size <= hanRun.size(); size++) {
            for (int i = 0; i <= hanRun.size() - size; i++) {
                StringBuilder term = new StringBuilder();
                for (int j = 0; j < size; j++) {
                    term.append(hanRun.get(i + j));
                }
                terms.add(term.toString());
            }
        }
        hanRun.clear();
    }

    private static boolean isAsciiLetterOrDigit(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z') || (codePoint >= '0' && codePoint <= '9');
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }
}
