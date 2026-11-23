package com.rheinmetal.tianshu.dubhe.agent.loop;

/**
 * A deterministic size estimate for agent context bookkeeping.
 *
 * <p>It exists so clamping and compaction decisions are testable without a tokenizer service. CJK characters are
 * counted as one token each; other characters as three tenths of a token. That over-estimates English slightly and
 * under-estimates nothing badly, which is the safe direction for a ceiling.</p>
 */
public final class DubheAgentTokenEstimator {

    private DubheAgentTokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0.0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            tokens += weightOf(codePoint);
            index += Character.charCount(codePoint);
        }
        return (int) Math.ceil(tokens);
    }

    /** The weight of one code point; clamping needs the same rule without re-estimating substrings. */
    public static double weightOf(int codePoint) {
        return isWide(codePoint) ? 1.0 : 0.3;
    }

    private static boolean isWide(int codePoint) {
        return codePoint >= 0x2E80;
    }
}