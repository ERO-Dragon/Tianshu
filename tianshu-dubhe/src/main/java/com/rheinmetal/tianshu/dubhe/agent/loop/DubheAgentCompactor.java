package com.rheinmetal.tianshu.dubhe.agent.loop;

import java.util.List;

/**
 * Turns evicted transcript entries into a progress digest (Codex-style auto-compaction).
 *
 * <p>An interface, not an implementation, so the transcript stays testable without a model: the model-backed
 * compactor is wired when the agent runner is assembled. Implementations must be bounded — the transcript rejects
 * a digest larger than its budget.</p>
 */
@FunctionalInterface
public interface DubheAgentCompactor {

    /**
     * @param goal     the task question, available even on the first compaction
     * @param previous the digest being replaced, or {@code null} on the first compaction
     * @param evicted  entries removed from the transcript, oldest first
     */
    DubheAgentDigest compact(String goal, DubheAgentDigest previous, List<DubheAgentEntry> evicted);
}