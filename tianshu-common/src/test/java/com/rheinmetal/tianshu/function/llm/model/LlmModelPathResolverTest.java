package com.rheinmetal.tianshu.function.llm.model;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.model.LlmModelInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmModelPathResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void catalogFileWinsOverOtherGgufFiles() throws Exception {
        Path modelDir = modelDir("chat-model");
        Files.createDirectories(modelDir);
        Files.writeString(modelDir.resolve("catalog.gguf"), "catalog");
        Files.writeString(modelDir.resolve("aaa.gguf"), "other");
        LlmModelInfo info = model("chat-model", "catalog.gguf");

        LlmModelPathResolver resolver = new LlmModelPathResolver(
                configuration("chat-model", ""),
                name -> info,
                name -> null
        );

        assertEquals(modelDir.resolve("catalog.gguf"), resolver.resolveChatModel());
    }

    @Test
    void deterministicFirstGgufIsUsedWhenCatalogFileIsAbsent() throws Exception {
        Path modelDir = modelDir("chat-model");
        Files.createDirectories(modelDir);
        Files.writeString(modelDir.resolve("b.gguf"), "b");
        Files.writeString(modelDir.resolve("A.gguf"), "a");

        LlmModelPathResolver resolver = new LlmModelPathResolver(
                configuration("chat-model", ""),
                name -> null,
                name -> null
        );

        assertEquals(modelDir.resolve("A.gguf"), resolver.resolveChatModel());
    }

    @Test
    void blankSelectionDoesNotGuessAFile() {
        LlmModelPathResolver resolver = new LlmModelPathResolver(
                configuration("", ""),
                name -> null,
                name -> null
        );

        assertNull(resolver.resolveChatModel());
        assertNull(resolver.resolveEmbeddingModel());
    }

    @Test
    void selectionCannotEscapeModelRoot() {
        LlmModelPathResolver resolver = new LlmModelPathResolver(
                configuration("../outside.gguf", ""),
                name -> null,
                name -> null
        );

        assertThrows(IllegalArgumentException.class, resolver::resolveChatModel);
    }

    @Test
    void modelBudgetsComeFromCatalogMetadata() {
        LlmModelInfo chat = model("chat-model", "chat.gguf");
        chat.contextSize = 8192;
        chat.promptTokenBudget = 6000;
        LlmModelInfo embedding = model("embedding-model", "embedding.gguf");
        embedding.contextSize = 512;
        LlmModelPathResolver resolver = new LlmModelPathResolver(
                configuration("chat-model", "embedding-model"),
                name -> chat,
                name -> embedding
        );

        assertEquals(8192, resolver.chatContextSize());
        assertEquals(6000, resolver.promptTokenBudget());
        assertEquals(512, resolver.embeddingContextSize());
    }

    private Path modelDir(String name) {
        return tempDir.resolve("llm").resolve("model").resolve(name);
    }

    private LlmConfiguration configuration(String chatModel, String embeddingModel) {
        return new LlmConfiguration() {
            @Override
            public boolean isLlmEnabled() {
                return true;
            }

            @Override
            public String getCustomLlmName() {
                return chatModel;
            }

            @Override
            public String getLlmEmbeddingModelName() {
                return embeddingModel;
            }

            @Override
            public Path getLlmBasePath() {
                return tempDir.resolve("llm");
            }
        };
    }

    private static LlmModelInfo model(String name, String file) {
        LlmModelInfo info = new LlmModelInfo();
        info.name = name;
        info.modelFile = file;
        return info;
    }
}
