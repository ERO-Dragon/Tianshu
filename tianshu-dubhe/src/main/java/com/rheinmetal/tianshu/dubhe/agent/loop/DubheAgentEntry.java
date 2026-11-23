package com.rheinmetal.tianshu.dubhe.agent.loop;

/**
 * One line of the agent's task transcript.
 *
 * <p>The kinds exist so compaction can keep the brief and the digest unconditionally while evicting the oldest
 * tool rounds first.</p>
 *
 * @param kind what this entry is
 * @param text the entry's text, already clamped by the transcript for tool results
 */
public record DubheAgentEntry(Kind kind, String text) {

    public enum Kind {
        /** The delegated question; compaction never evicts it. */
        BRIEF,
        /** A structured progress digest produced by compaction. */
        DIGEST,
        /** A skill call (name and arguments). */
        TOOL_CALL,
        /** What the skill returned, clamped to the per-result ceiling. */
        TOOL_RESULT,
        /** The agent's own words for a step. */
        ASSISTANT
    }

    public DubheAgentEntry {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        text = text == null ? "" : text;
    }

    public int tokens() {
        return DubheAgentTokenEstimator.estimate(text);
    }

    public boolean isToolRoundPart() {
        return kind == Kind.TOOL_CALL || kind == Kind.TOOL_RESULT;
    }
}