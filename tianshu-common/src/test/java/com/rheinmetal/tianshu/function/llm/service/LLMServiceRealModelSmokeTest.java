package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.llm.KvCacheType;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "tianshu.llm.smoke", matches = "true")
class LLMServiceRealModelSmokeTest {
    private static final String DEFAULT_MODEL_FILE = "Qwen3-0.6B-Q4_K_M.gguf";
    private static final String DEFAULT_EMBEDDING_MODEL_FILE = "bge-large-zh-v1.5-q4_k_m.gguf";
    private static final String CHAT_MODEL_NAME = "qwen3-0.6b-smoke";
    private static final String EMBEDDING_MODEL_NAME = "bge-large-zh-v1.5";

    @Test
    void chatAndEmbeddingWithRealModelsReturnUsableResults() throws Exception {
        Path modelPath = resolveSmokeModelPath("tianshu.llm.smoke.model", DEFAULT_MODEL_FILE);
        Path embeddingModelPath = resolveSmokeModelPath("tianshu.llm.smoke.embeddingModel", DEFAULT_EMBEDDING_MODEL_FILE);
        assertTrue(Files.isRegularFile(modelPath), "LLM smoke model does not exist: " + modelPath);
        assertTrue(Files.isRegularFile(embeddingModelPath), "LLM embedding smoke model does not exist: " + embeddingModelPath);

        JavaLlamaServer server = JavaLlamaServer.builder()
                .model(modelPath.toString())
                .modelAlias(CHAT_MODEL_NAME)
                .modelProfile("auto")
                .contextSize(4096)
                .chatThreads(2)
                .chatMaxQueueSize(1)
                .taskThreads(1)
                .requestTimeoutSeconds(90)
                .cacheTypeK(KvCacheType.Q8_0)
                .cacheTypeV(KvCacheType.Q8_0)
                .gpuLayers(0)
                .embeddingModel(embeddingModelPath.toString())
                .embeddingAlias(EMBEDDING_MODEL_NAME)
                .embeddingContextSize(512)
                .embeddingThreads(2)
                .embeddingGpuLayers(0)
                .build();

        try {
            server.start();
            LLMService service = LLMService.builder()
                    .env(new FakeGameEnvironment())
                    .config(new SmokeConfig())
                    .aiService(server)
                    .embeddingConfigured(true)
                    .usePersistentCache(false)
                    .build();

            LLMRequest request = LLMRequest.ofMessage(
                    MessageItem.system("You are a concise test assistant. Answer in one short sentence."),
                    MessageItem.user("Say hello in Chinese.")
            );
            request.setMaxTokens(0);
            request.setTemperature(0.1f);
            request.setThinking(false);

            LLMRuntimeSnapshotPayload beforeEmbedRuntime = service.runtimeSnapshotResponse("runtime-before-embed-smoke").runtimeSnapshot();
            String text = service.chat(request).text();
            LLMPrimitiveResultPayload embed = service.embedResponse("embed-smoke", List.of(
                    "玩家正在主世界调试 AX 记忆检索。",
                    "IR named object index loaded from cache"
            ), true, true);
            LLMRuntimeSnapshotPayload runtime = service.runtimeSnapshotResponse("runtime-smoke").runtimeSnapshot();

            assertTrue(beforeEmbedRuntime.embeddingAvailable(), "Runtime snapshot should expose configured embedding before first embed");
            assertFalse(text == null || text.isBlank(), "LLM smoke response should not be blank");
            assertEquals(LLMPrimitiveResultPayload.STATUS_COMPLETED, embed.status());
            assertEquals(2, embed.embedResults().size());
            for (LLMPrimitiveResultPayload.EmbedResultPayload result : embed.embedResults()) {
                assertTrue(result.dimension() > 0, "Embedding dimension should be positive");
                assertEquals(result.dimension(), result.vector().length, "Embedding vector length should match dimension");
                assertEquals(EMBEDDING_MODEL_NAME, result.embeddingModelName());
                assertFalse(result.embeddingNamespace().isBlank(), "Embedding namespace should not be blank");
            }
            assertTrue(runtime.embeddingAvailable(), "Runtime snapshot should expose embedding availability");
            assertEquals(embed.embedResults().get(0).dimension(), runtime.embeddingDimension());
            assertEquals(EMBEDDING_MODEL_NAME, runtime.embeddingModelName());
            assertEquals(embed.embedResults().get(0).embeddingNamespace(), runtime.embeddingNamespace());

            writeSmokeReport(modelPath, embeddingModelPath, text, embed, beforeEmbedRuntime, runtime);
        } finally {
            server.shutdown();
        }
    }

    private static Path resolveSmokeModelPath(String propertyName, String defaultFileName) {
        String configured = System.getProperty(propertyName, defaultFileName);
        Path input = Path.of(configured);
        if (input.isAbsolute()) {
            return input.normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path inCwd = cwd.resolve(input).normalize();
        if (Files.isRegularFile(inCwd)) {
            return inCwd;
        }
        Path inParent = cwd.getParent() == null ? inCwd : cwd.getParent().resolve(input).normalize();
        if (Files.isRegularFile(inParent)) {
            return inParent;
        }
        return inCwd;
    }

    private static void writeSmokeReport(
            Path modelPath,
            Path embeddingModelPath,
            String chatText,
            LLMPrimitiveResultPayload embed,
            LLMRuntimeSnapshotPayload beforeEmbedRuntime,
            LLMRuntimeSnapshotPayload runtime
    ) throws Exception {
        Path reportPath = Path.of("build", "reports", "llm", "real-model-smoke.md");
        Files.createDirectories(reportPath.getParent());

        LLMPrimitiveResultPayload.EmbedResultPayload first = embed.embedResults().isEmpty()
                ? null
                : embed.embedResults().get(0);
        StringBuilder report = new StringBuilder();
        report.append("# LLM Real Model Smoke\n\n");
        report.append("## Models\n\n");
        report.append("- chatModel: `").append(modelPath.toAbsolutePath().normalize()).append("`\n");
        report.append("- embeddingModel: `").append(embeddingModelPath.toAbsolutePath().normalize()).append("`\n");
        report.append("- embeddingAlias: `").append(runtime.embeddingModelName()).append("`\n");
        report.append("- embeddingNamespace: `").append(runtime.embeddingNamespace()).append("`\n\n");
        report.append("## Chat\n\n");
        report.append("```text\n").append(chatText == null ? "" : chatText.strip()).append("\n```\n\n");
        report.append("## Embedding\n\n");
        report.append("- beforeEmbedAvailable: `").append(beforeEmbedRuntime.embeddingAvailable()).append("`\n");
        report.append("- beforeEmbedDimension: `").append(beforeEmbedRuntime.embeddingDimension()).append("`\n");
        report.append("- status: `").append(embed.status()).append("`\n");
        report.append("- resultCount: `").append(embed.embedResults().size()).append("`\n");
        report.append("- runtimeAvailable: `").append(runtime.embeddingAvailable()).append("`\n");
        report.append("- runtimeDimension: `").append(runtime.embeddingDimension()).append("`\n");
        if (first != null) {
            report.append("- firstDimension: `").append(first.dimension()).append("`\n");
            report.append("- firstVectorPreview: `").append(vectorPreview(first.vector(), 8)).append("`\n");
        }
        report.append('\n');
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
    }

    private static String vectorPreview(float[] vector, int limit) {
        if (vector == null || vector.length == 0) {
            return "";
        }
        int count = Math.min(vector.length, Math.max(0, limit));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format(Locale.ROOT, "%.5f", vector[i]));
        }
        if (vector.length > count) {
            sb.append(", ...");
        }
        return sb.toString();
    }

    private static final class FakeGameEnvironment implements IGameEnvironment {
        @Override public void displayMessageToPlayer(String message) {}
        @Override public void executeOnMainThread(Runnable task) { task.run(); }
        @Override public Path getGameDirectory() { return Path.of("."); }
        @Override public boolean isClientSide() { return true; }
        @Override public void openFolder(Path dir) {}
        @Override public void info(String msg) {}
        @Override public void warn(String msg) {}
        @Override public void error(String msg, Throwable t) {}
        @Override public com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink diagnostics() { return com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP; }
    }

    private static final class SmokeConfig implements LlmConfiguration {
        @Override public boolean isLlmEnabled() { return true; }
        @Override public String getCustomLlmName() { return CHAT_MODEL_NAME; }
        @Override public Path getLlmBasePath() { return Path.of("."); }
        @Override public String getLlmEmbeddingModelName() { return EMBEDDING_MODEL_NAME; }
    }
}
