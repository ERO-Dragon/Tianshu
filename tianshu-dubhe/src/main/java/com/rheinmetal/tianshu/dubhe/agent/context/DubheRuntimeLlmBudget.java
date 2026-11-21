package com.rheinmetal.tianshu.dubhe.agent.context;

import com.rheinmetal.tianshu.weaving.contract.LLMRuntimeSnapshotPayload;

/**
 * The budget an AX turn actually runs on.
 *
 * <p>Two producers exist and only one of them is a per-turn truth:</p>
 * <ul>
 *   <li>{@link #fromSnapshot} — the normal path. The LLM reports its {@code contextTokenBudget}, and
 *   {@link DubheMemoryWindowPolicy#fromBudget} derives a policy for exactly that total, so every scaled
 *   number (retention window, compression watermarks, summary block) follows the real model.</li>
 *   <li>{@link #fallback} — the failed-lookup path. It reuses the construction-time policy, which is
 *   the {@link DubheMemoryWindowPolicy#DEFAULT} contract floor unless the module supplied something else.</li>
 * </ul>
 * <p>Consumers must treat whatever is handed to them as the resolved value for this turn and never
 * read {@link DubheMemoryWindowPolicy#DEFAULT} on the side.</p>
 */
public record DubheRuntimeLlmBudget(
        DubheMemoryWindowPolicy memoryPolicy,
        DubheContextBudget contextBudget
) {
    public DubheRuntimeLlmBudget {
        memoryPolicy = memoryPolicy == null ? DubheMemoryWindowPolicy.DEFAULT : memoryPolicy;
        contextBudget = contextBudget == null ? DubheContextBudget.fromPolicy(memoryPolicy) : contextBudget;
    }

    /** Budget of a turn whose LLM context total could not be resolved. */
    public static DubheRuntimeLlmBudget fallback(DubheMemoryWindowPolicy fallbackPolicy) {
        DubheMemoryWindowPolicy policy = fallbackPolicy == null ? DubheMemoryWindowPolicy.DEFAULT : fallbackPolicy;
        return new DubheRuntimeLlmBudget(policy, DubheContextBudget.fromPolicy(policy));
    }

    /** Budget of a turn built from the LLM-reported context total; this overrides the fallback. */
    public static DubheRuntimeLlmBudget fromSnapshot(LLMRuntimeSnapshotPayload snapshot, DubheMemoryWindowPolicy fallbackPolicy) {
        DubheMemoryWindowPolicy fallback = fallbackPolicy == null ? DubheMemoryWindowPolicy.DEFAULT : fallbackPolicy;
        int contextTokenBudget = snapshot == null ? 0 : snapshot.contextTokenBudget();
        if (contextTokenBudget <= 0) {
            return fallback(fallback);
        }
        DubheMemoryWindowPolicy policy = DubheMemoryWindowPolicy.fromBudget(
                contextTokenBudget,
                fallback.shortTermChatBlockLimit(),
                fallback.conversationPauseMillis()
        );
        return new DubheRuntimeLlmBudget(policy, DubheContextBudget.fromPolicy(policy));
    }
}
