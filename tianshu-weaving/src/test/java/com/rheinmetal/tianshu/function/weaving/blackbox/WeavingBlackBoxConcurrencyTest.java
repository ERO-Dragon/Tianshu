package com.rheinmetal.tianshu.function.weaving.blackbox;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency and resource pressure: many requests share one client, every answer must complete, and
 * closing the module must leave none of its worker threads behind.
 */
class WeavingBlackBoxConcurrencyTest {
    private static final int CONCURRENT_REQUESTS = 24;
    private static final int SEQUENTIAL_REQUESTS = 30;

    @TempDir
    Path tempDir;

    @Test
    void concurrentRequestsAllCompleteWithTheirOwnAnswer() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) -> {
            String asked = body.getAsJsonArray("messages").get(0).getAsJsonObject()
                    .get("content").getAsString();
            List<String> frames = FakeOpenAiEndpoint.completion(
                    "ack:" + asked, 5, 2, "reasoning_content", "");
            FakeOpenAiEndpoint.sse(exchange, frames.toArray(String[]::new));
        })) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                List<BlackBoxDriver.Pending> pending = new ArrayList<>();
                for (int index = 0; index < CONCURRENT_REQUESTS; index++) {
                    pending.add(driver.promptAsync(request("concurrent-" + index, index % 2 == 0)));
                }
                for (int index = 0; index < pending.size(); index++) {
                    var response = driver.await(pending.get(index));
                    assertEquals("COMPLETED", response.result().status(), response.result().errorMessage());
                    assertEquals("ack:hello concurrent-" + index, response.result().text(),
                            "every concurrent request gets its own answer");
                }

                assertEquals(CONCURRENT_REQUESTS, endpoint.requests().size());
                assertEquals(CONCURRENT_REQUESTS, driver.moduleService().usageSnapshot().total().requests());
            }
        }
    }

    @Test
    void sequentialLoadLeavesNoRemoteThreadsAfterShutdown() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange,
                        FakeOpenAiEndpoint.completion("ok", 3, 1, "reasoning_content", "")
                                .toArray(String[]::new)))) {
            BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()));
            try {
                for (int index = 0; index < SEQUENTIAL_REQUESTS; index++) {
                    assertEquals("COMPLETED",
                            driver.prompt(request("sequential-" + index, index % 2 == 0)).result().status());
                }
            } finally {
                driver.close();
            }

            long deadline = System.currentTimeMillis() + 5_000L;
            while (remoteThreads() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertEquals(0, remoteThreads(), "the client's worker threads are released on shutdown");
        }
    }

    private static long remoteThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("tianshu-llm-remote"))
                .count();
    }

    private static LLMPromptRequestPayload request(String marker, boolean stream) {
        return new LLMPromptRequestPayload(
                marker,
                List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello " + marker)),
                stream,
                false,
                32,
                LLMPromptRequestPayload.SamplingPayload.empty()
        );
    }
}