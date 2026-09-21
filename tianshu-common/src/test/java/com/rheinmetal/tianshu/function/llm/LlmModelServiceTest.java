package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.model.LlmModelInfo;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmModelServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void detectsModelContentAndDeletesModelDirectory() throws Exception {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        LlmModelInfo info = modelInfo("unit-model", "model.gguf");
        Path modelDir = config.getLlmBasePath().resolve("model").resolve(info.name);
        Files.createDirectories(modelDir);
        Files.writeString(modelDir.resolve(info.getModelFile()), "fake model");
        Files.writeString(modelDir.resolve("sidecar.txt"), "metadata");

        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            LlmModelService service = new LlmModelService(env, config, executors);

            assertTrue(service.hasModelContent(info));
            assertEquals("fake model".length(), service.modelSizeBytes(info));
            assertTrue(service.deleteModel(info));
            assertFalse(Files.exists(modelDir));
            assertFalse(service.hasModelContent(info));
        }
    }

    @Test
    void nullOrBlankModelDoesNotResolveToDirectory() {
        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            LlmModelService service = new LlmModelService(
                    new TestLlmSupport.FakeGameEnvironment(),
                    new TestLlmSupport.FakeConfig(tempDir),
                    executors
            );

            assertNull(service.resolveModelDir(null));
            assertEquals(0L, service.modelSizeBytes(modelInfo("", "")));
        }
    }

    @Test
    void startupCleanupKeepsPartialModelFileForResume() throws Exception {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        String modelName = "qwen3.5-2B-MTP-unsloth-Q4_K_M";
        Path partial = config.getLlmBasePath()
                .resolve("model")
                .resolve(modelName)
                .resolve("Qwen3.5-2B-Q4_K_M.gguf.downloading");
        Files.createDirectories(partial.getParent());
        Files.writeString(partial, "partial", StandardCharsets.UTF_8);

        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            new LlmModelService(new TestLlmSupport.FakeGameEnvironment(), config, executors);
            Thread.sleep(100L);
            assertTrue(Files.exists(partial));
        }
    }

    private static LlmModelInfo modelInfo(String name, String modelFile) {
        LlmModelInfo info = new LlmModelInfo();
        info.name = name;
        info.modelFile = modelFile;
        return info;
    }
}
