package com.rheinmetal.tianshu.function.llm;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmProtocolAdapterCleanBoundaryTest {
    @Test
    void adapterDelegatesPromptMappingAndExecution() {
        Set<String> collaboratorTypes = Arrays.stream(LlmProtocolAdapter.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName())
                .collect(Collectors.toSet());

        assertTrue(collaboratorTypes.contains("LlmPromptPayloadMapper"), collaboratorTypes.toString());
        assertTrue(collaboratorTypes.contains("LlmPromptRequestHandler"), collaboratorTypes.toString());
    }

    @Test
    void adapterDoesNotOwnPromptExecutionInternals() {
        Set<String> methodNames = Arrays.stream(LlmProtocolAdapter.class.getDeclaredMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet());

        assertFalse(methodNames.contains("toLLMRequest"), methodNames.toString());
        assertFalse(methodNames.contains("startTaskStreamRequest"), methodNames.toString());
        assertFalse(methodNames.contains("streamThinkingContent"), methodNames.toString());
    }
}
