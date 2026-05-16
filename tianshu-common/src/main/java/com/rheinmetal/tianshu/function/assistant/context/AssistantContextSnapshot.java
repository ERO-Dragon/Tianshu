package com.rheinmetal.tianshu.function.assistant.context;

import com.rheinmetal.tianshu.function.assistant.memory.AssistantMemorySnapshot;
import com.rheinmetal.tianshu.function.assistant.rag.DynamicRagCandidate;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;

import java.util.List;

public record AssistantContextSnapshot(AssistantScope scope, AssistantMemorySnapshot memory, List<DynamicRagCandidate> dynamicRagCandidates, String providedContext, boolean memoryRagAvailable) {
    public AssistantContextSnapshot {
        if (scope == null) {
            scope = AssistantScope.unknown();
        }
        if (memory == null) {
            memory = AssistantMemorySnapshot.empty(scope);
        }
        dynamicRagCandidates = dynamicRagCandidates == null ? List.of() : List.copyOf(dynamicRagCandidates);
        providedContext = providedContext == null ? "" : providedContext.trim();
    }

    public AssistantContextSnapshot(AssistantScope scope, AssistantMemorySnapshot memory, List<DynamicRagCandidate> dynamicRagCandidates, String providedContext) {
        this(scope, memory, dynamicRagCandidates, providedContext, false);
    }
}
