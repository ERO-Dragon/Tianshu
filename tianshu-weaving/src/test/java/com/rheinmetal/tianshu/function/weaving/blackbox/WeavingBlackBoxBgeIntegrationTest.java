package com.rheinmetal.tianshu.function.weaving.blackbox;

import com.rheinmetal.tianshu.core.runtime.RuntimeCapabilityState;
import com.rheinmetal.tianshu.function.weaving.LlmRuntimeCapabilities;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManagePayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveResultPayload;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional integration run against the real BGE-M3 GGUF.
 *
 * <p>Gated by {@code -Dblackbox.bge=<path to bge-m3-Q8_0.gguf>} so the normal suite never downloads or
 * loads 600 MB. When the property is present, this boots the module exactly like a player would: the
 * embedding catalog name is {@code bge-m3}, the file lives under the module's model directory, and the
 * lane loads through {@code LocalEmbeddingEngine} on the CPU.</p>
 */
class WeavingBlackBoxBgeIntegrationTest {

    @Test
    void theRealBgeModelAnswersEmbedAndVectorRag() throws Exception {
        String configured = System.getProperty("blackbox.bge", System.getenv("TIANSHU_BLACKBOX_BGE"));
        Assumptions.assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)),
                "set -Dblackbox.bge=<bge-m3-Q8_0.gguf> to run the real embedding integration");

        Path home = Path.of(System.getProperty("blackbox.home", "build/blackbox/bge-home")).toAbsolutePath();
        Path modelDir = home.resolve("llm").resolve("model");
        Files.createDirectories(modelDir);
        // A catalog selection that is not a .gguf resolves to a directory named after it.
        Path targetDir = modelDir.resolve("bge-m3");
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve("bge-m3-Q8_0.gguf");
        if (!Files.isRegularFile(target)) {
            Files.copy(Path.of(configured), target, StandardCopyOption.REPLACE_EXISTING);
        }

        BlackBoxConfig config = new BlackBoxConfig(home, "http://127.0.0.1:1/v1")
                .embeddingModelName("bge-m3")
                .requestTimeoutSeconds(30);
        try (BlackBoxDriver driver = BlackBoxDriver.start(config)) {
            try {
                driver.awaitCapability(LlmRuntimeCapabilities.LLM_CACHE_MANAGE,
                        RuntimeCapabilityState.READY, 180_000L);
            } catch (AssertionError failure) {
                throw new AssertionError(failure.getMessage() + "\nmodule logs: " + driver.logs(), failure);
            }

            LLMPrimitiveResultPayload embedded = driver.primitive(
                    LLMPrimitiveQueryPayload.embed("bge-embed", List.of("the cat sat on the warm mat"), true, true));
            assertEquals("COMPLETED", embedded.status());
            LLMPrimitiveResultPayload.EmbedResultPayload vector = embedded.embedResults().get(0);
            assertEquals(1024, vector.dimension(), "BGE-M3 dense vectors are 1024-dimensional");
            assertEquals(1024, vector.vector().length);
            assertEquals("bge-m3", vector.embeddingModelName());

            LLMPrimitiveResultPayload status = driver.primitive(LLMPrimitiveQueryPayload.status("bge-status"));
            assertTrue(status.runtimeSnapshot().embeddingAvailable());
            assertEquals(1024, status.runtimeSnapshot().embeddingDimension(),
                    "the dimension is learned from the first vector");

            LLMCacheManageResultPayload registered = driver.cache(
                    LLMCacheManagePayload.registerLibrary("kb", "modid", "SHARED", List.of()));
            assertTrue(registered.success(), registered.errorMessage());

            LLMCacheManageResultPayload upserted = driver.cache(
                    LLMCacheManagePayload.upsertEntry("kb", "cat", "The cat sat on the warm mat.", new float[0]));
            assertTrue(upserted.success(), upserted.errorMessage());

            LLMCacheManageResultPayload searched = driver.cache(
                    LLMCacheManagePayload.searchUid("kb", "cat", 4, 0.01f));
            assertTrue(searched.success(), searched.errorMessage());
            assertFalse(searched.hits().isEmpty(), "the real embedding lane must return a vector hit");
            assertEquals("The cat sat on the warm mat.",
                    searched.hits().get(0).entries().get(0).content());
        }
    }
}