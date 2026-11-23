package com.rheinmetal.tianshu.dubhe.agent.delegate;

import java.util.List;

/**
 * Recognizes a player agreeing to continue a paused task.
 *
 * <p>Deterministic and deliberately narrow: the sentence is normalized (punctuation and whitespace removed,
 * lower-cased) and compared against the resource word list for exact equality. Anything longer or different is
 * treated as a new request, so "继续查暮色森林" is a new delegation rather than a resume — that keeps the resume
 * decision explainable.</p>
 */
public final class DubheResumeIntent {

    private DubheResumeIntent() {
    }

    public static boolean accepts(String query, List<String> words) {
        String normalized = normalize(query);
        if (normalized.isEmpty() || words == null || words.isEmpty()) {
            return false;
        }
        for (String word : words) {
            if (normalized.equals(normalize(word))) {
                return true;
            }
        }
        return false;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }
}