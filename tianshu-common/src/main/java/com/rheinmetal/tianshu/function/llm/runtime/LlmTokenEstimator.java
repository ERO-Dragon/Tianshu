package com.rheinmetal.tianshu.function.llm.runtime;

import com.rheinmetal.tianshu.libs.llm.ChatMessage;

import java.util.List;

/**
 * Language-aware prompt-token estimate, used whenever the backend exposes no tokenizer.
 *
 * <p>A cloud endpoint never ships its tokenizer, so exact counting is impossible before a request
 * is sent. What we do instead: estimate from character classes, then let the provider's reported
 * {@code prompt_tokens} pull the estimate back into line through {@link #calibrate(int, int)}.
 *
 * <p>Chinese text is far denser than Latin text per character, so a single global characters-per-token
 * ratio would underestimate a Chinese prompt by roughly a factor of three. That is why the estimate
 * splits CJK from everything else.
 */
public final class LlmTokenEstimator {
    /** Tokens per CJK character. Measured against DeepSeek usage; deliberately a little high. */
    private static final double CJK_TOKENS_PER_CHAR = 0.62D;

    /** Tokens per non-CJK character, including Latin letters, digits and ASCII punctuation. */
    private static final double OTHER_TOKENS_PER_CHAR = 0.30D;

    /** Per-message framing (role marker, separators) charged by chat-completion APIs. */
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    /** Per-request framing (assistant priming turn). */
    private static final int REQUEST_OVERHEAD_TOKENS = 3;

    /** How fast the calibration factor follows observed provider truth. */
    private static final double CALIBRATION_ALPHA = 0.2D;

    private static final double CALIBRATION_MIN = 0.8D;
    private static final double CALIBRATION_MAX = 3.5D;

    private volatile double calibration = 1.0D;

    /** Current scale factor applied on top of the character-class estimate. */
    public double calibration() {
        return calibration;
    }

    /** Prompt tokens for a whole message list, excluding the completion. */
    public int estimate(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return REQUEST_OVERHEAD_TOKENS;
        }
        double total = REQUEST_OVERHEAD_TOKENS;
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            total += MESSAGE_OVERHEAD_TOKENS;
            total += estimateText(message.content);
            total += estimateText(message.role);
        }
        return Math.max(1, (int) Math.round(total * calibration));
    }

    /** Raw character-class estimate for a single string, without calibration. */
    public double estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0D;
        }
        int cjk = 0;
        int other = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                cjk++;
            } else if (!Character.isWhitespace(codePoint)) {
                other++;
            }
        }
        return cjk * CJK_TOKENS_PER_CHAR + other * OTHER_TOKENS_PER_CHAR;
    }

    /**
     * Feeds provider truth back into the estimate.
     *
     * @param estimated what {@link #estimate(List)} predicted for that request
     * @param actual    what the provider reported as {@code prompt_tokens}
     */
    public void calibrate(int estimated, int actual) {
        if (estimated <= 0 || actual <= 0) {
            return;
        }
        double observed = (double) actual / (double) estimated;
        double next = calibration + CALIBRATION_ALPHA * (observed * calibration - calibration);
        calibration = Math.max(CALIBRATION_MIN, Math.min(CALIBRATION_MAX, next));
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.BOPOMOFO;
    }
}
