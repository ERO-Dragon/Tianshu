package com.rheinmetal.tianshu.function.auxilium.core.context;

import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;

/**
 * The budget an AX turn actually runs on.
 *
 * <p>Two producers exist and only one of them is a per-turn truth:</p>
 * <ul>
 *   <li>{@link #fromSnapshot} — the normal path. The LLM reports its {@code contextTokenBudget}, and
 *   {@link AXMemoryWindowPolicy#fromBudget} derives a policy for exactly that total, so every scaled
 *   number (retention window, compression watermarks, summary block) follows the real model.</li>
 *   <li>{@link #fallback} — the failed-lookup path. It reuses the construction-time policy, which is
 *   the {@link AXMemoryWindowPolicy#DEFAULT} contract floor unless the module supplied something else.</li>
 * </ul>
 * <p>Consumers must treat whatever is handed to them as the resolved value for this turn and never
 * read {@link AXMemoryWindowPolicy#DEFAULT} on the side.</p>
 */
public record AXRuntimeLlmBudget(
        AXMemoryWindowPolicy memoryPolicy,
        AXContextBudget contextBudget
) {
    public AXRuntimeLlmBudget {
        memoryPolicy = memoryPolicy == null ? AXMemoryWindowPolicy.DEFAULT : memoryPolicy;
        contextBudget = contextBudget == null ? AXContextBudget.fromPolicy(memoryPolicy) : contextBudget;
    }

    /** Budget of a turn whose LLM context total could not be resolved. */
    public static AXRuntimeLlmBudget fallback(AXMemoryWindowPolicy fallbackPolicy) {
        AXMemoryWindowPolicy policy = fallbackPolicy == null ? AXMemoryWindowPolicy.DEFAULT : fallbackPolicy;
        return new AXRuntimeLlmBudget(policy, AXContextBudget.fromPolicy(policy));
    }

    /** Budget of a turn built from the LLM-reported context total; this overrides the fallback. */
    public static AXRuntimeLlmBudget fromSnapshot(LLMRuntimeSnapshotPayload snapshot, AXMemoryWindowPolicy fallbackPolicy) {
        AXMemoryWindowPolicy fallback = fallbackPolicy == null ? AXMemoryWindowPolicy.DEFAULT : fallbackPolicy;
        int contextTokenBudget = snapshot == null ? 0 : snapshot.contextTokenBudget();
        if (contextTokenBudget <= 0) {
            return fallback(fallback);
        }
        AXMemoryWindowPolicy policy = AXMemoryWindowPolicy.fromBudget(
                contextTokenBudget,
                fallback.shortTermChatBlockLimit(),
                fallback.conversationPauseMillis()
        );
        return new AXRuntimeLlmBudget(policy, AXContextBudget.fromPolicy(policy));
    }
}
