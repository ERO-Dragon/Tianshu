package com.rheinmetal.tianshu.function.intake.repair;

import com.rheinmetal.tianshu.function.intake.repair.core.IRBaseUtils;

/**
 * One registered trigger word, compiled to the tokens the matcher compares.
 *
 * <p>Matching works on tokens, not on their concatenation: comparing the joined pinyin string with
 * {@code contains} makes any word whose pinyin is a prefix or substring of another match it ("ai" inside
 * "kai" would fire a one-character wake word on an unrelated sentence).</p>
 */
record IrCompiledVoiceWord(String rawText, String[] tokens) {
    IrCompiledVoiceWord {
        if (rawText == null) rawText = "";
        rawText = rawText.trim();
        tokens = tokens == null ? new String[0] : tokens.clone();
    }

    boolean isEmpty() {
        return rawText.isBlank() || tokens.length == 0;
    }

    String joined() {
        return IRBaseUtils.joinTokens(tokens);
    }
}