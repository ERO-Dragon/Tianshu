package com.rheinmetal.tianshu.weaving.service;

import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMToolCallPayload;

import java.util.List;

/**
 * One message of a request, as the transport sends it.
 *
 * <p>The foundry type this replaces carries a role and a body and nothing else, which is enough for a
 * plain conversation and not enough for a tool-using one: an assistant turn that asked for a call has
 * to be replayed with that call, and a result is a {@code tool} message naming the call it answers.
 * Keeping the tool fields here means the request body can be built from this record alone, without the
 * client reaching back into the protocol payload.</p>
 *
 * @param role       {@code system} / {@code user} / {@code assistant} / {@code tool}
 * @param content    the visible text of the message, never {@code null}
 * @param toolCallId id of the call a {@code tool} message answers; empty otherwise
 * @param toolCalls  calls an {@code assistant} message replays; empty otherwise
 */
public record LlmChatMessage(
        String role,
        String content,
        String toolCallId,
        List<LLMToolCallPayload> toolCalls
) {
    public LlmChatMessage {
        role = role == null || role.isBlank() ? "user" : role.trim().toLowerCase(java.util.Locale.ROOT);
        content = content == null ? "" : content;
        toolCallId = toolCallId == null ? "" : toolCallId.trim();
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** A message with no tool involvement. */
    public LlmChatMessage(String role, String content) {
        this(role, content, "", List.of());
    }

    public static LlmChatMessage of(String role, String content) {
        return new LlmChatMessage(role, content);
    }

    public static LlmChatMessage system(String content) {
        return new LlmChatMessage("system", content);
    }

    public static LlmChatMessage user(String content) {
        return new LlmChatMessage("user", content);
    }

    public static LlmChatMessage assistant(String content) {
        return new LlmChatMessage("assistant", content);
    }

    /** The assistant turn that asked for calls; it must precede their results. */
    public static LlmChatMessage assistantToolCalls(String content, List<LLMToolCallPayload> calls) {
        return new LlmChatMessage("assistant", content, "", calls);
    }

    /** The result of one call, as a {@code tool} message. */
    public static LlmChatMessage toolResult(String toolCallId, String content) {
        return new LlmChatMessage("tool", content, toolCallId, List.of());
    }

    /** The transport form of one protocol message. */
    static LlmChatMessage from(LLMPromptRequestPayload.MessageItemPayload item) {
        return new LlmChatMessage(item.role(), item.content(), item.toolCallId(), item.toolCalls());
    }
}