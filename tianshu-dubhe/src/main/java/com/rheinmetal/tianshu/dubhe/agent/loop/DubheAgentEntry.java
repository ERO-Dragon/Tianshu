package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.rheinmetal.tianshu.weaving.contract.LLMToolCallPayload;

import java.util.List;

/**
 * One entry of the agent's task transcript, shaped for replay as provider messages.
 *
 * <p>Native tool calling needs the assistant message that requested the calls and the tool messages that answer
 * them, so an assistant entry carries its calls and a tool result carries the id it answers. Text alone would make
 * the replay impossible.</p>
 *
 * @param kind       what this entry is
 * @param text       text content; the assistant's words, the tool result, or the digest body
 * @param toolCallId the call this result answers; blank for other kinds
 * @param toolCalls  calls requested by this assistant message; empty for other kinds
 */
public record DubheAgentEntry(Kind kind, String text, String toolCallId, List<LLMToolCallPayload> toolCalls) {

    public enum Kind {
        /** The delegated question; compaction never evicts it. */
        BRIEF,
        /** A structured progress digest produced by compaction. */
        DIGEST,
        /** The agent's words for a step, optionally requesting tool calls. */
        ASSISTANT,
        /** What a skill returned, clamped to the per-result ceiling. */
        TOOL_RESULT
    }

    public DubheAgentEntry {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        text = text == null ? "" : text;
        toolCallId = toolCallId == null ? "" : toolCallId.trim();
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public DubheAgentEntry(Kind kind, String text) {
        this(kind, text, "", List.of());
    }

    public static DubheAgentEntry assistant(String text) {
        return new DubheAgentEntry(Kind.ASSISTANT, text, "", List.of());
    }

    public static DubheAgentEntry assistant(String text, List<LLMToolCallPayload> calls) {
        return new DubheAgentEntry(Kind.ASSISTANT, text, "", calls);
    }

    public static DubheAgentEntry toolResult(String toolCallId, String text) {
        return new DubheAgentEntry(Kind.TOOL_RESULT, text, toolCallId, List.of());
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public int tokens() {
        return DubheAgentTokenEstimator.estimate(text);
    }
}