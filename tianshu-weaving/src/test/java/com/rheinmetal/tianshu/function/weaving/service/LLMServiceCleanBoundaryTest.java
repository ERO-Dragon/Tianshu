package com.rheinmetal.tianshu.function.weaving.service;

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
    void serviceOwnsTransportEmbeddingAndMetadataOnly() {
        Set<String> collaboratorTypes = Arrays.stream(LLMService.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName())
                .collect(Collectors.toSet());

        assertTrue(collaboratorTypes.contains("LlmInferenceClient"), collaboratorTypes.toString());
        assertTrue(collaboratorTypes.contains("EmbeddingRuntime"), collaboratorTypes.toString());
        assertTrue(collaboratorTypes.contains("LlmServiceMetadata"), collaboratorTypes.toString());
        assertFalse(collaboratorTypes.contains("LlmRagService"), "retrieval is a sibling service, not a dependency");
    }

    @Test
    void serviceContainsNoLanguageSpecificPromptFallback() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/function/weaving/service/LLMService.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("以下是与当前请求相关的检索上下文"));
        assertFalse(source.contains("你对玩家说"));
    }
}
