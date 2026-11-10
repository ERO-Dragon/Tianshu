package com.rheinmetal.tianshu.function.weaving.blackbox;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.function.weaving.settings.LlmReasoningProfile;
import com.rheinmetal.tianshu.function.weaving.settings.LlmReasoningWire;
import com.rheinmetal.tianshu.function.weaving.settings.LlmWireCompat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Black-box chat scenarios: the real module, its real OpenAI-compatible client, and a real socket.
 */
class WeavingBlackBoxChatTest {
    @TempDir
    Path tempDir;

    @Test
    void aBlockingRequestReturnsTheProviderAnswerOverARealSocket() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange, completion("Hello", 12, 3, "reasoning_content", "")))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                BlackBoxDriver.Response response = driver.prompt(payload("req-blocking", false));

                assertEquals("COMPLETED", response.result().status());
                assertEquals("Hello", response.result().text());
                assertEquals(12, response.result().usage().promptTokens());
                assertEquals(3, response.result().usage().completionTokens());
                assertTrue(response.chunks().isEmpty(), "a blocking request publishes no chunks");
            }

            JsonObject sent = endpoint.onlyRequest();
            assertTrue(sent.get("stream").getAsBoolean(), "the lane always streams internally");
            assertTrue(sent.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
            assertEquals("user", sent.getAsJsonArray("messages").get(0).getAsJsonObject()
                    .get("role").getAsString());
            assertEquals("hello", sent.getAsJsonArray("messages").get(0).getAsJsonObject()
                    .get("content").getAsString());
        }
    }

    @Test
    void aStreamingRequestPublishesIndexedChunksAndOneTerminalFrame() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange,
                        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}",
                        "data: [DONE]"))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                BlackBoxDriver.Response response = driver.prompt(payload("req-stream", true, 0, true));

                assertEquals("COMPLETED", response.result().status());
                assertEquals("Hello", response.result().text());
                assertEquals("think", response.result().thinkingContent(), "reasoning travels separately");

                List<LLMPromptStreamChunkPayload> ordered = response.orderedChunks();
                assertEquals(3, ordered.size(), "thinking, one coalesced text chunk, terminal");
                assertEquals("think", ordered.get(0).thinkingContent());
                assertEquals("Hello", ordered.get(1).text(),
                        "the two provider deltas are coalesced into one text chunk");
                assertTrue(ordered.get(2).finished());
                assertEquals("COMPLETED", ordered.get(2).finishType());
                assertEquals(List.of(0, 1, 2), ordered.stream()
                        .map(LLMPromptStreamChunkPayload::index).toList(),
                        "indices are the caller's ordering contract");
            }
        }
    }

    @Test
    void reasoningTierAndWireShapeAreSentAsConfigured() throws Exception {
        LlmReasoningProfile deepSeek = new LlmReasoningProfile(
                LlmReasoningWire.DEEPSEEK_THINKING, true, List.of("low", "high", "max"), 1);
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange, completion("ok", 5, 1, "reasoning_content", "hmm")))) {
            BlackBoxConfig config = new BlackBoxConfig(tempDir, endpoint.baseUrl())
                    .reasoning(deepSeek)
                    .wireCompat(new LlmWireCompat(false, LlmWireCompat.MaxTokensField.MAX_COMPLETION_TOKENS));
            try (BlackBoxDriver driver = BlackBoxDriver.start(config)) {
                driver.prompt(payload("req-wire", false, 3, true));
            }

            JsonObject sent = endpoint.onlyRequest();
            assertFalse(sent.has("stream_options"), "the entry asked for a gateway without stream_options");
            assertEquals(64, sent.get("max_completion_tokens").getAsInt());
            assertFalse(sent.has("max_tokens"));
            assertEquals("enabled", sent.getAsJsonObject("thinking").get("type").getAsString());
            assertEquals("max", sent.get("reasoning_effort").getAsString(),
                    "tier 2 of [none, low, high, max] names max");
        }
    }

    @Test
    void reasoningArrivesWhenAProviderUsesTheOtherSpelling() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange, completion("answer", 4, 2, "reasoning", "because")))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                LLMPromptResultPayload result = driver.prompt(payload("req-reasoning", false)).result();

                assertEquals("answer", result.text());
                assertEquals("because", result.thinkingContent(),
                        "reasoning is also read from the plain 'reasoning' field");
            }
        }
    }

    @Test
    void tierZeroAsksForTheNonThinkingSlot() throws Exception {
        LlmReasoningProfile deepSeek = new LlmReasoningProfile(
                LlmReasoningWire.DEEPSEEK_THINKING, true, List.of("low", "high", "max"), 1);
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange, completion("ok", 5, 1, "reasoning_content", "")))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(
                    new BlackBoxConfig(tempDir, endpoint.baseUrl()).reasoning(deepSeek))) {
                driver.prompt(payload("req-tier-0", false, 0, false));
            }

            JsonObject sent = endpoint.onlyRequest();
            assertEquals("disabled", sent.getAsJsonObject("thinking").get("type").getAsString());
            assertFalse(sent.has("reasoning_effort"), "the non-thinking slot sends no effort name");
        }
    }

    @Test
    void aProviderErrorBecomesAFailedResultWithItsStatus() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.json(exchange, 401, "{\"error\":{\"message\":\"bad key\"}}"))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                LLMPromptResultPayload result = driver.prompt(payload("req-401", false)).result();

                assertEquals("FAILED", result.status());
                assertEquals("LLM_INFERENCE_FAILED", result.errorCode());
                assertTrue(result.errorMessage().contains("401"), result.errorMessage());
                assertTrue(result.text().isEmpty());
            }
        }
    }

    @Test
    void aServerErrorBecomesAFailedResult() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.json(exchange, 503, "{\"error\":{\"message\":\"busy\"}}"))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                LLMPromptResultPayload result = driver.prompt(payload("req-503", false)).result();

                assertEquals("FAILED", result.status());
                assertEquals("LLM_INFERENCE_FAILED", result.errorCode());
                assertTrue(result.errorMessage().contains("503"), result.errorMessage());
            }
        }
    }

    @Test
    void malformedFramesAreSkippedWithoutFailingTheStream() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange,
                        "data: {not json",
                        ": keep-alive",
                        "event: message",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
                        "data: [DONE]"))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                LLMPromptResultPayload result = driver.prompt(payload("req-malformed", false)).result();

                assertEquals("ok", result.text());
                assertEquals("COMPLETED", result.status());
            }
        }
    }

    @Test
    void aStreamWithoutDoneCompletesWhenTheSocketEnds() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange,
                        "data: {\"choices\":[{\"delta\":{\"content\":\"done-by-eof\"}}]}"))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                LLMPromptResultPayload result = driver.prompt(payload("req-eof", false)).result();

                assertEquals("done-by-eof", result.text());
                assertEquals("COMPLETED", result.status());
            }
        }
    }

    @Test
    void aFilteredAnswerIsReportedAsAFailure() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange,
                        "data: {\"choices\":[{\"delta\":{\"content\":\"par\"}}]}",
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}",
                        "data: [DONE]"))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                LLMPromptResultPayload result = driver.prompt(payload("req-filter", false)).result();

                assertEquals("FAILED", result.status());
                assertEquals("LLM_INFERENCE_FAILED", result.errorCode());
                assertTrue(result.errorMessage().contains("REMOTE_LLM_CONTENT_FILTERED"),
                        result.errorMessage());
            }
        }
    }

    @Test
    void aSilentStreamIsStoppedByTheRequestDeadline() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) -> {
            FakeOpenAiEndpoint.beginSse(exchange);
            // The client clamps a configured timeout to at least five seconds; stay silent past that.
            Thread.sleep(6_000L);
        })) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(
                    new BlackBoxConfig(tempDir, endpoint.baseUrl()).requestTimeoutSeconds(1))) {
                LLMPromptResultPayload result = driver.prompt(payload("req-stall", false)).result();

                assertEquals("FAILED", result.status());
                assertTrue(result.errorMessage().contains("REMOTE_LLM_STREAM_TIMEOUT"),
                        result.errorMessage());
            }
        }
    }

    @Test
    void anEmptyMessageListIsRejectedBeforeAnySocketIsOpened() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange, "data: [DONE]"))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                LLMPromptRequestPayload empty = new LLMPromptRequestPayload(
                        "req-empty", List.of(), false, false, 0,
                        LLMPromptRequestPayload.SamplingPayload.empty());

                LLMPromptResultPayload result = driver.prompt(empty).result();

                assertEquals("FAILED", result.status());
                assertEquals("LLM_EMPTY_MESSAGES", result.errorCode());
                assertTrue(endpoint.requests().isEmpty(), "the provider is never called for a refused request");
            }
        }
    }

    @Test
    void usageIsRecordedAndTheCacheSplitIsDerivedFromTheNestedShape() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange,
                        "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":2,"
                                + "\"prompt_tokens_details\":{\"cached_tokens\":8}}}",
                        "data: [DONE]"))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                driver.prompt(payload("req-usage", false));

                var total = driver.moduleService().usageSnapshot().total();
                assertEquals(1, total.requests());
                assertEquals(20, total.inputTokens());
                assertEquals(8, total.cacheHitInputTokens());
                assertEquals(12, total.cacheMissInputTokens(), "the rest of the prompt is the miss");
                assertEquals(1, total.cacheReportedRequests());
                assertEquals(2, total.outputTokens());
            }
        }
    }

    @Test
    void aBlockingRequestCanAlsoBeCancelledMidFlight() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) -> {
            FakeOpenAiEndpoint.beginSse(exchange);
            FakeOpenAiEndpoint.write(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n");
            Thread.sleep(5_000L);
        })) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                BlackBoxDriver.Pending pending = driver.promptAsync(payload("req-cancel-blocking", false));
                for (int i = 0; i < 200 && endpoint.requests().isEmpty(); i++) {
                    Thread.sleep(10L);
                }
                assertFalse(endpoint.requests().isEmpty(), "the blocking request must reach the provider");

                driver.cancel(pending.request());
                assertEquals("CANCELLED", driver.await(pending).result().status());
            }
        }
    }

    @Test
    void aBurstOfTokensIsBatchedBelowTheProtocolRateLimit() throws Exception {
        int frames = 400;
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) -> {
            FakeOpenAiEndpoint.beginSse(exchange);
            for (int index = 0; index < frames; index++) {
                FakeOpenAiEndpoint.write(exchange,
                        "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n");
            }
            FakeOpenAiEndpoint.write(exchange, "data: [DONE]\n\n");
        })) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                BlackBoxDriver.Response response;
                try {
                    response = driver.prompt(payload("req-long", true, 0, false));
                } catch (AssertionError failure) {
                    throw new AssertionError(failure.getMessage() + "\n" + driver.diagnostics(), failure);
                }

                assertEquals(frames, response.result().text().length(), "no token is lost");
                assertTrue(response.chunks().size() < frames / 4,
                        "a burst is coalesced into a handful of envelopes, got " + response.chunks().size());
                var ordered = response.orderedChunks();
                assertTrue(ordered.get(ordered.size() - 1).finished());
                assertEquals(ordered.size() - 1, ordered.get(ordered.size() - 1).index());
                for (int index = 0; index < ordered.size(); index++) {
                    assertEquals(index, ordered.get(index).index(), "indices stay contiguous");
                }
            }
        }
    }

    @Test
    void aCancelEnvelopeStopsAnInFlightStream() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) -> {
            FakeOpenAiEndpoint.beginSse(exchange);
            FakeOpenAiEndpoint.write(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n");
            Thread.sleep(5_000L);
        })) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                BlackBoxDriver.Pending pending = driver.promptAsync(payload("req-cancel", true, 0, false));
                for (int i = 0; i < 200 && pending.chunks().isEmpty(); i++) {
                    Thread.sleep(10L);
                }
                assertFalse(pending.chunks().isEmpty(), "the stream must have started before it is cancelled");

                driver.cancel(pending.request());
                BlackBoxDriver.Response response = driver.await(pending);

                assertEquals("CANCELLED", response.result().status());
                LLMPromptStreamChunkPayload terminal = response.orderedChunks()
                        .get(response.orderedChunks().size() - 1);
                assertTrue(terminal.finished());
                assertEquals("CANCELLED", terminal.finishType());
            }
        }
    }

    @Test
    void anOutOfRangeTierIsClampedToTheTopOfTheLadder() throws Exception {
        LlmReasoningProfile deepSeek = new LlmReasoningProfile(
                LlmReasoningWire.DEEPSEEK_THINKING, true, List.of("low", "high", "max"), 1);
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange, completion("ok", 5, 1, "reasoning_content", "")))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(
                    new BlackBoxConfig(tempDir, endpoint.baseUrl()).reasoning(deepSeek))) {
                driver.prompt(payload("req-tier-99", false, 99, false));
            }

            assertEquals("max", endpoint.onlyRequest().get("reasoning_effort").getAsString());
        }
    }

    @Test
    void anEmptyProviderAnswerCompletesWithEmptyText() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange, "data: [DONE]"))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                LLMPromptResultPayload result = driver.prompt(payload("req-empty-answer", false)).result();

                assertEquals("COMPLETED", result.status());
                assertTrue(result.text().isEmpty());
            }
        }
    }

    @Test
    void twoRequestsShareTheClientAndAccumulateUsage() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.sse(exchange, completion("hi", 3, 1, "reasoning_content", "")))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                driver.prompt(payload("req-first", false));
                driver.prompt(payload("req-second", true, 0, true));

                assertEquals(2, driver.moduleService().usageSnapshot().total().requests());
                assertEquals(2, endpoint.requests().size());
            }
        }
    }

    private static String[] completion(String content, int promptTokens, int completionTokens,
                                       String thinkingField, String thinking) {
        List<String> frames = FakeOpenAiEndpoint.completion(content, promptTokens, completionTokens,
                thinkingField, thinking);
        return frames.toArray(String[]::new);
    }

    private LLMPromptRequestPayload payload(String requestId, boolean stream) {
        return payload(requestId, stream, 0, false);
    }

    private LLMPromptRequestPayload payload(String requestId, boolean stream, int reasoningTier,
                                            boolean captureThinking) {
        return new LLMPromptRequestPayload(
                requestId,
                List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello")),
                stream,
                captureThinking,
                64,
                reasoningTier,
                LLMPromptRequestPayload.SamplingPayload.empty()
        );
    }
}