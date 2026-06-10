package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.llm.KvCacheType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "tianshu.llm.smoke", matches = "true")
class LLMServiceRealModelSmokeTest {
    private static final Path DEFAULT_MODEL_PATH = Path.of("D:/Minecraft/Qwen3-0.6B-Q4_K_M.gguf");

    @Test
    void chatWithRealQwenModelReturnsText() throws Exception {
        Path modelPath = Path.of(System.getProperty("tianshu.llm.smoke.model", DEFAULT_MODEL_PATH.toString()));
        assertTrue(Files.isRegularFile(modelPath), "LLM smoke model does not exist: " + modelPath);

        JavaLlamaServer server = JavaLlamaServer.builder()
                .model(modelPath.toString())
                .modelAlias("qwen3-0.6b-smoke")
                .modelProfile("auto")
                .chatContext(4096)
                .chatThreads(2)
                .chatMaxQueueSize(1)
                .taskContext(4096)
                .taskThreads(1)
                .taskMaxQueueSize(0)
                .taskSuspendOnChat(true)
                .requestTimeoutSeconds(90)
                .cacheTypeK(KvCacheType.Q8_0)
                .cacheTypeV(KvCacheType.Q8_0)
                .gpuLayers(0)
                .build();

        try {
            server.start();
            LLMService service = LLMService.builder()
                    .env(new FakeGameEnvironment())
                    .aiService(server)
                    .usePersistentCache(false)
                    .build();

            LLMRequest request = LLMRequest.ofMessage(
                    MessageItem.system("You are a concise test assistant. Answer in one short sentence."),
                    MessageItem.user("Say hello in Chinese.")
            );
            request.setMaxTokens(1);
            request.setTemperature(0.1f);
            request.setThinking(false);

            String text = service.chat(request).text();

            assertFalse(text == null, "LLM smoke response should not be null");
        } finally {
            server.shutdown();
        }
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
    }
}
