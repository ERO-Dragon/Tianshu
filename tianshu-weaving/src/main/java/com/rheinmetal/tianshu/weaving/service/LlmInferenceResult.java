package com.rheinmetal.tianshu.weaving.service;

import com.rheinmetal.tianshu.foundry.llm.LlmTokenUsage;

/**
 * What one inference call produced, including whether the provider cut the answer short.
 *
 * <p>The generated text, its usage and its reasoning already exist as
 * {@link com.rheinmetal.tianshu.foundry.llm.LlmGenerationResult}; what that type cannot say is
 * whether the answer ended because it was finished or because it hit the provider's completion cap.
 * That distinction changes what the caller may do with the text — a capped answer is not a whole one
 * — so it travels with the result instead of being re-derived from a log line.</p>
 *
 * <p>{@code truncated} is only ever set for a <em>completed</em> answer that stopped at the cap
 * ({@code finish_reason=length}). A cancelled or failed call keeps {@code false}: there the text is a
 * prefix of an interrupted call, which the status already states.</p>
 *
 * @param text            visible answer, never {@code null}
 * @param usage           provider-reported usage, zero when the provider reported none
 * @param thinkingContent structured reasoning, never {@code null}
 * @param truncated       whether the provider stopped at its completion cap rather than at the end
 */
public record LlmInferenceResult(String text, LlmTokenUsage usage, String thinkingContent, boolean truncated) {

    public LlmInferenceResult {
        text = text == null ? "" : text;
        usage = usage == null ? new LlmTokenUsage(0, 0) : usage;
        thinkingContent = thinkingContent == null ? "" : thinkingContent;
    }
}