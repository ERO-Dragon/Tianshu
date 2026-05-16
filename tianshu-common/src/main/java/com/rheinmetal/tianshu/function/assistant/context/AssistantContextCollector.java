package com.rheinmetal.tianshu.function.assistant.context;

import com.rheinmetal.tianshu.function.assistant.AssistantRequest;
import com.rheinmetal.tianshu.function.assistant.memory.AssistantMemorySnapshot;
import com.rheinmetal.tianshu.function.assistant.memory.AssistantMemorySystem;
import com.rheinmetal.tianshu.function.assistant.rag.DynamicRagCandidateBuilder;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;

import java.util.List;

public final class AssistantContextCollector {
    private final AssistantMemorySystem memorySystem;
    private final DynamicRagCandidateBuilder ragCandidateBuilder;

    public AssistantContextCollector(AssistantMemorySystem memorySystem, DynamicRagCandidateBuilder ragCandidateBuilder) {
        this.memorySystem = memorySystem;
        this.ragCandidateBuilder = ragCandidateBuilder;
    }

    public AssistantContextSnapshot collect(AssistantScope scope, AssistantRequest request) {
        AssistantScope effectiveScope = scope == null ? AssistantScope.unknown() : scope;
        AssistantMemorySnapshot memory = memorySystem == null ? AssistantMemorySnapshot.empty(effectiveScope) : memorySystem.load(effectiveScope);
        return new AssistantContextSnapshot(
                effectiveScope,
                memory,
                ragCandidateBuilder == null ? List.of() : ragCandidateBuilder.build(effectiveScope, request),
                request == null ? "" : request.providedContext(),
                memorySystem != null && memorySystem.hasMemoryRagEntries(effectiveScope)
        );
    }
}
