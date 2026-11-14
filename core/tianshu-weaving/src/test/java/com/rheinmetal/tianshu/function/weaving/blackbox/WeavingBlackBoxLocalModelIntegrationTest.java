package com.rheinmetal.tianshu.function.weaving.blackbox;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional run against a real local model server (llama.cpp's OpenAI endpoint).
 *
 * <p>Gated by {@code TIANSHU_BLACKBOX_LLM_URL}; when set, the module talks to that endpoint instead of
 * the scripted one, so a real tokenizer, real streaming and a real model complete the black-box
 * picture. The server is expected to alias its model as {@code test-model} (or
 * {@code TIANSHU_BLACKBOX_LLM_MODEL} names it).</p>
 */
class WeavingBlackBoxLocalModelIntegrationTest {

    @Test
    void aRealLocalModelCompletesBlockingAndStreamingRequests(@TempDir Path tempDir) throws Exception {
        String url = System.getenv("TIANSHU_BLACKBOX_LLM_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "set TIANSHU_BLACKBOX_LLM_URL=http://127.0.0.1:<port>/v1 to run the local model");
        String model = System.getenv().getOrDefault("TIANSHU_BLACKBOX_LLM_MODEL", "test-model");

        BlackBoxConfig config = new BlackBoxConfig(tempDir, url)
                .model(model)
                .requestTimeoutSeconds(60);
        try (BlackBoxDriver driver = BlackBoxDriver.start(config)) {
            LLMPromptResultPayload blocking = driver.prompt(request("local-blocking", false, false)).result();
            assertEquals("COMPLETED", blocking.status(), blocking.errorMessage());
            assertFalse(blocking.text().isBlank(), "the local model must produce some text");

            BlackBoxDriver.Response streaming = driver.prompt(request("local-streaming", true, true));
            assertEquals("COMPLETED", streaming.result().status(), streaming.result().errorMessage());
            assertFalse(streaming.result().text().isBlank());
            assertTrue(streaming.chunks().size() >= 2, "a real stream has at least one frame and a terminal");
            assertTrue(streaming.orderedChunks().get(streaming.chunks().size() - 1).finished());
        }
    }

    private static LLMPromptRequestPayload request(String requestId, boolean stream, boolean captureThinking) {
        return new LLMPromptRequestPayload(
                requestId,
                List.of(LLMPromptRequestPayload.MessageItemPayload.user(
                        "Answer with one short sentence: what color is a clear daytime sky?")),
                stream,
                captureThinking,
                64,
                null,
                LLMPromptRequestPayload.SamplingPayload.empty()
        );
    }
}