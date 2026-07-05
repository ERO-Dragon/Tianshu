package com.rheinmetal.tianshu.function.auxilium.core.context;

import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;

public record AXRuntimeLlmBudget(
        AXMemoryWindowPolicy memoryPolicy,
        AXContextBudget contextBudget
) {
    public AXRuntimeLlmBudget {
        memoryPolicy = memoryPolicy == null ? AXMemoryWindowPolicy.DEFAULT : memoryPolicy;
        contextBudget = contextBudget == null ? AXContextBudget.fromPolicy(memoryPolicy) : contextBudget;
    }

    public static AXRuntimeLlmBudget fallback(AXMemoryWindowPolicy fallbackPolicy) {
        AXMemoryWindowPolicy policy = fallbackPolicy == null ? AXMemoryWindowPolicy.DEFAULT : fallbackPolicy;
        return new AXRuntimeLlmBudget(policy, AXContextBudget.fromPolicy(policy));
    }

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
