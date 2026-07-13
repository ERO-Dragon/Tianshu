package com.rheinmetal.tianshu.function.llm.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMServiceCleanBoundaryTest {
    @Test
    void serviceDelegatesRagRequestPreparationAndRuntimeInspection() {
        Set<String> collaboratorTypes = Arrays.stream(LLMService.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName())
                .collect(Collectors.toSet());

        assertTrue(collaboratorTypes.contains("LlmRagService"), collaboratorTypes.toString());
        assertTrue(collaboratorTypes.contains("LlmRequestPreparer"), collaboratorTypes.toString());
        assertTrue(collaboratorTypes.contains("LlmRuntimeInspector"), collaboratorTypes.toString());
    }

    @Test
    void productionServiceDoesNotImplementAWholeFallbackGameConfig() {
        Set<String> nestedTypes = Arrays.stream(LLMService.class.getDeclaredClasses())
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());

        assertFalse(nestedTypes.contains("DefaultLlmConfig"), nestedTypes.toString());
    }

    @Test
    void serviceContainsNoLanguageSpecificRagFallback() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/function/llm/service/LLMService.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("以下是与当前请求相关的检索上下文"));
    }
}
