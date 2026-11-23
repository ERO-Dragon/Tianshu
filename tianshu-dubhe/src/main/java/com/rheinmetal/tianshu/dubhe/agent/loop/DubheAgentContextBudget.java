package com.rheinmetal.tianshu.dubhe.agent.loop;

/**
 * The agent task's own context ceiling (G49), independent of the dialogue window tier.
 *
 * <p>It is derived from the model's real window: reserve room for this call's output, then keep a fixed headroom
 * for the next call before compaction starts. A skill's tool result is clamped separately, so one noisy result
 * cannot crowd out the task brief.</p>
 *
 * @param windowTokens the model's real context window
 */
public record DubheAgentContextBudget(int windowTokens) {

    /** Room kept for the answer being generated. */
    public static final int OUTPUT_RESERVE = 4_096;
    /** Compaction starts when the context grows within this distance of the budget. */
    public static final int MIN_HEADROOM = 8_192;
    public static final double HEADROOM_RATIO = 0.18;
    /** A single tool result never exceeds this; head and tail are kept, the middle is folded. */
    public static final int TOOL_RESULT_TOKENS = 4_096;
    /** Recent tool rounds kept verbatim after compaction. */
    public static final int KEEP_RECENT_ROUNDS = 3;
    /** A progress digest must stay small, otherwise compaction buys nothing. */
    public static final int DIGEST_TOKENS = 2_048;

    public DubheAgentContextBudget {
        if (windowTokens <= OUTPUT_RESERVE) {
            throw new IllegalArgumentException(
                    "agent window " + windowTokens + " leaves no room after the output reserve");
        }
    }

    public static DubheAgentContextBudget forWindow(int windowTokens) {
        return new DubheAgentContextBudget(windowTokens);
    }

    /** Tokens available to the request after reserving output room. */
    public int budgetTokens() {
        return windowTokens - OUTPUT_RESERVE;
    }

    /** Context size that triggers compaction; never below half the budget, so a small window stays usable. */
    public int compactAtTokens() {
        int headroom = Math.max(MIN_HEADROOM, (int) Math.ceil(budgetTokens() * HEADROOM_RATIO));
        return Math.max(budgetTokens() / 2, budgetTokens() - headroom);
    }

    public int digestTokens() {
        return DIGEST_TOKENS;
    }

    public int toolResultTokens() {
        // A small window must clamp harder, otherwise one result could exceed the compaction point alone.
        return Math.min(TOOL_RESULT_TOKENS, Math.max(512, budgetTokens() / 3));
    }
}