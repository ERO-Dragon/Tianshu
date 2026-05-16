package com.rheinmetal.tianshu.function.assistant.fact;

import com.rheinmetal.tianshu.function.assistant.AssistantRequest;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;

import java.util.List;

public interface RuntimeFactProvider {
    String providerId();

    default List<RuntimeFact> refreshForQuestion(AssistantScope scope, AssistantRequest request) {
        return List.of();
    }
}
