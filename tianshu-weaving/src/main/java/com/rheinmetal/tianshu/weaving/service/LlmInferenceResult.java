package com.rheinmetal.tianshu.weaving.service;

import com.rheinmetal.tianshu.foundry.llm.LlmTokenUsage;
import com.rheinmetal.tianshu.weaving.contract.LLMToolCallPayload;

import java.util.List;

/**
 * What one inference call produced, including whether the provider cut the answer short and which
 * tools the model asked for.
 *
 * <p>The generated text, its usage and its reasoning already exist as
 * {@link com.rheinmetal.tianshu.foundry.llm.LlmGenerationResult}; what that type cannot say is
 * whether the answer ended because it was finished or because it hit the provider's completion cap,
 * and it has no place for a tool request at all. Both facts change what the caller may do with the
 * answer — a capped answer is not a whole one, and a tool request is not an answer — so they travel
 * with the result instead of being re-derived from a log line or from the text.</p>
 *
 * <p>{@code truncated} is only ever set for a <em>completed</em> answer that stopped at the cap
 * ({@code finish_reason=length}). A cancelled or failed call keeps {@code false}: there the text is a
 * prefix of an interrupted call, which the status already states.</p>
 *
 * @param text            visible answer, never {@code null}; usually empty when a tool was requested
 * @param usage           provider-reported usage, zero when the provider reported none
 * @param thinkingContent structured reasoning, never {@code null}
 * @param toolCalls       calls the model asked for, empty when it answered in text
 * @param truncated       whether the provider stopped at its completion cap rather than at the end
 */
public record LlmInferenceResult(
        String text,
        LlmTokenUsage usage,
        String thinkingContent,
        List<LLMToolCallPayload> toolCalls,
        boolean truncated
) {

    public LlmInferenceResult {
        text = text == null ? "" : text;
        usage = usage == null ? new LlmTokenUsage(0, 0) : usage;
        thinkingContent = thinkingContent == null ? "" : thinkingContent;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** A result with no tool request, which is what every plain answer is. */
    public LlmInferenceResult(String text, LlmTokenUsage usage, String thinkingContent, boolean truncated) {
        this(text, usage, thinkingContent, List.of(), truncated);
    }
}