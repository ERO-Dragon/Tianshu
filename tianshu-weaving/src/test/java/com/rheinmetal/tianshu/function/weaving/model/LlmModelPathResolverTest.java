package com.rheinmetal.tianshu.function.weaving.model;

import com.rheinmetal.tianshu.function.weaving.settings.LlmConfiguration;
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
        Path modelDir = modelDir("embedding-model");
        Files.createDirectories(modelDir);
        Files.writeString(modelDir.resolve("catalog.gguf"), "catalog");
        Files.writeString(modelDir.resolve("aaa.gguf"), "other");
        LlmModelInfo info = model("embedding-model", "catalog.gguf");

        LlmModelPathResolver resolver = new LlmModelPathResolver(
                configuration("embedding-model"),
                name -> info
        );

        assertEquals(modelDir.resolve("catalog.gguf"), resolver.resolveEmbeddingModel());
    }

    @Test
    void deterministicFirstGgufIsUsedWhenCatalogFileIsAbsent() throws Exception {
        Path modelDir = modelDir("embedding-model");
        Files.createDirectories(modelDir);
        Files.writeString(modelDir.resolve("b.gguf"), "b");
        Files.writeString(modelDir.resolve("A.gguf"), "a");

        LlmModelPathResolver resolver = new LlmModelPathResolver(
                configuration("embedding-model"),
                name -> null
        );

        assertEquals(modelDir.resolve("A.gguf"), resolver.resolveEmbeddingModel());
    }

    @Test
    void blankSelectionDoesNotGuessAFile() {
        LlmModelPathResolver resolver = new LlmModelPathResolver(
                configuration(""),
                name -> null
        );

        assertNull(resolver.resolveEmbeddingModel());
    }

    @Test
    void selectionCannotEscapeModelRoot() {
        LlmModelPathResolver resolver = new LlmModelPathResolver(
                configuration("../outside.gguf"),
                name -> null
        );

        assertThrows(IllegalArgumentException.class, resolver::resolveEmbeddingModel);
    }

    @Test
    void embeddingContextSizeComesFromCatalogMetadata() {
        LlmModelInfo embedding = model("embedding-model", "embedding.gguf");
        embedding.contextSize = 512;
        LlmModelPathResolver resolver = new LlmModelPathResolver(
                configuration("embedding-model"),
                name -> embedding
        );

        assertEquals(512, resolver.embeddingContextSize());
    }

    private Path modelDir(String name) {
        return tempDir.resolve("llm").resolve("model").resolve(name);
    }

    private LlmConfiguration configuration(String embeddingModel) {
        return new LlmConfiguration() {
            @Override
            public boolean isLlmEnabled() {
                return true;
            }

            @Override
            public String getCustomLlmName() {
                return "";
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
