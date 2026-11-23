package com.rheinmetal.tianshu.dubhe.agent.delegate;

import java.util.List;

/**
 * Recognizes the player agreeing to a first-use tier question.
 *
 * <p>Same narrow semantics as {@link DubheResumeIntent}: exact equality after normalization, so "同意，但别搬箱子"
 * is a new request rather than an agreement. The words live in the prompt resource
 * ({@code agent.consent.accept}); this class only compares.</p>
 */
public final class DubheConsentIntent {

    private DubheConsentIntent() {
    }

    public static boolean accepts(String query, List<String> words) {
        String normalized = DubheResumeIntent.normalize(query);
        if (normalized.isEmpty() || words == null || words.isEmpty()) {
            return false;
        }
        for (String word : words) {
            if (normalized.equals(DubheResumeIntent.normalize(word))) {
                return true;
            }
        }
        return false;
    }
}