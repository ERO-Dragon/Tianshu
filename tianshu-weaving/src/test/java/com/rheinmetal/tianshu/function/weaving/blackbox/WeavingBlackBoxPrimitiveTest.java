package com.rheinmetal.tianshu.function.weaving.blackbox;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.function.weaving.settings.LlmReasoningProfile;
import com.rheinmetal.tianshu.function.weaving.settings.LlmReasoningWire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code LLM.PRIMITIVE_QUERY} without a vector lane: counting, status, and the documented failures. */
class WeavingBlackBoxPrimitiveTest {
    @TempDir
    Path tempDir;

    @Test
    void tokenCountMeasuresTheCallersMessages() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            LLMPrimitiveResultPayload result = driver.primitive(LLMPrimitiveQueryPayload.tokenCount(
                    "count", "", List.of(LLMPrimitiveQueryPayload.MessageItemPayload.user("hello world"))));

            assertEquals("COMPLETED", result.status());
            assertEquals("TOKEN_COUNT", result.queryType());
            assertTrue(result.tokenCount() > 0);
        }
    }

    @Test
    void statusReportsTheDeclaredWindowWithoutEmbedding() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            LLMPrimitiveResultPayload result = driver.primitive(LLMPrimitiveQueryPayload.status("status"));

            assertEquals("COMPLETED", result.status());
            var snapshot = result.runtimeSnapshot();
            assertTrue(snapshot.ready());
            assertEquals(200_000, snapshot.contextSize());
            assertTrue(snapshot.contextTokenBudget() > 0);
            assertTrue(snapshot.contextTokenBudget() < snapshot.contextSize(),
                    "the budget is smaller than the window by the output reserve and headroom");
            assertFalse(snapshot.embeddingAvailable());
            assertEquals(0, snapshot.reasoningTierCount(), "the black-box config declares no ladder");
        }
    }

    @Test
    void statusReportsTheConfiguredReasoningLadder() throws Exception {
        LlmReasoningProfile ladder = new LlmReasoningProfile(
                LlmReasoningWire.OPENAI_EFFORT, false, List.of("low", "medium", "high"), 1);
        BlackBoxConfig config = new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1").reasoning(ladder);
        try (BlackBoxDriver driver = BlackBoxDriver.start(config)) {
            var snapshot = driver.primitive(LLMPrimitiveQueryPayload.status("ladder"))
                    .runtimeSnapshot();

            assertEquals(3, snapshot.reasoningTierCount());
            assertFalse(snapshot.reasoningIncludesNoThinking());
            assertEquals(1, snapshot.reasoningDefaultTier());
            assertEquals(List.of("low", "medium", "high"), snapshot.reasoningLevels());
        }
    }

    @Test
    void embeddingWithoutALoadedModelFailsWithItsOwnCode() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            LLMPrimitiveResultPayload result = driver.primitive(
                    LLMPrimitiveQueryPayload.embed("embed", List.of("hello"), true, true));

            assertEquals("FAILED", result.status());
            assertEquals("LLM_EMBEDDING_UNAVAILABLE", result.errorCode());
        }
    }

    @Test
    void anUnknownQueryTypeIsAFailedResult() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            LLMPrimitiveResultPayload result = driver.primitive(new LLMPrimitiveQueryPayload(
                    "unknown", "SOMETHING_ELSE", "", List.of(), List.of(), false, false));

            assertEquals("FAILED", result.status());
            assertEquals("UNKNOWN_QUERY_TYPE", result.errorCode());
        }
    }
}