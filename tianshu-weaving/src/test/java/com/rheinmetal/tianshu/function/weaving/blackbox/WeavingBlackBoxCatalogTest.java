package com.rheinmetal.tianshu.function.weaving.blackbox;

import com.rheinmetal.tianshu.function.weaving.catalog.LlmModelCatalogKeySource;
import com.rheinmetal.tianshu.function.weaving.catalog.LlmModelCatalogResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The live model list is fetched over the real HTTP seam, not through a stubbed client. */
class WeavingBlackBoxCatalogTest {
    @TempDir
    Path tempDir;

    @Test
    void theModelListIsFetchedFromTheEndpoint() throws Exception {
        try (FakeOpenAiEndpoint endpoint = FakeOpenAiEndpoint.start((body, exchange) ->
                FakeOpenAiEndpoint.json(exchange, 500, "{\"error\":\"not a chat request\"}"))) {
            try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, endpoint.baseUrl()))) {
                CountDownLatch answered = new CountDownLatch(1);
                AtomicReference<LlmModelCatalogResult> answer = new AtomicReference<>();

                driver.moduleService().requestModelCatalog(
                        endpoint.baseUrl(), "sk-black-box", LlmModelCatalogKeySource.TYPED, result -> {
                            answer.set(result);
                            answered.countDown();
                        });

                assertTrue(answered.await(10, TimeUnit.SECONDS), "the catalog request must answer");
                assertTrue(answer.get().success(), String.valueOf(answer.get().detail()));
                assertEquals(java.util.List.of("test-model"), answer.get().modelIds());
            }
        }
    }
}