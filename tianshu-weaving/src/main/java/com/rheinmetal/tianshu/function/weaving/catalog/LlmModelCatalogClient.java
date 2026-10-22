package com.rheinmetal.tianshu.function.weaving.catalog;

import java.util.function.Consumer;

/**
 * Async source of the model ids a configured endpoint serves.
 *
 * <p>This is the seam the settings page reads through: the page never builds an HTTP client and never
 * blocks the render thread, so a slow or hanging endpoint can only delay the catalog, not the frame.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>{@link #listModels} must return without blocking the caller and must do the network work on its
 *       own thread.</li>
 *   <li>{@link #listModels} must invoke the sink exactly once per call, from any thread.</li>
 *   <li>A failure is reported as the matching {@link LlmModelCatalogState} with its language key,
 *       never as an empty list: "the endpoint listed nothing", "the credential was rejected" and "the
 *       request failed" are different answers and the page says so.</li>
 *   <li>A blank credential is not a reason to skip the request. The page asks before anything is saved,
 *       so the provider's own answer is the only authority on whether a key is needed and whether it is
 *       accepted.</li>
 *   <li>The credential is a request header only. It must never be logged, echoed into a result or
 *       written into a detail.</li>
 * </ul>
 */
@FunctionalInterface
public interface LlmModelCatalogClient {
    /**
     * Placeholder client used until an implementation is bound. It answers immediately with
     * {@link LlmModelCatalogState#NOT_WIRED}, which the page renders as "not wired" instead of
     * pretending the provider has no models.
     */
    LlmModelCatalogClient NOT_WIRED = new LlmModelCatalogClient() {
        @Override
        public boolean wired() {
            return false;
        }

        @Override
        public void listModels(String baseUrl, String apiKey, LlmModelCatalogKeySource keySource,
                               Consumer<LlmModelCatalogResult> sink) {
            if (sink != null) {
                sink.accept(LlmModelCatalogResult.notWired());
            }
        }
    };

    /**
     * Whether an implementation is bound. A fetch against an unwired client is skipped entirely, so
     * the page cannot report a network failure it never attempted.
     */
    default boolean wired() {
        return true;
    }

    /**
     * Asks the provider at {@code baseUrl} for its model list.
     *
     * @param baseUrl   provider base URL, for example {@code https://api.deepseek.com/v1}
     * @param apiKey    bearer credential; may be blank, in which case the request is sent without an
     *                  {@code Authorization} header and the provider's answer decides
     * @param keySource where that credential came from; diagnostic metadata only, never read as a
     *                  credential and never part of the request
     * @param sink      answer sink, invoked exactly once
     */
    void listModels(String baseUrl, String apiKey, LlmModelCatalogKeySource keySource,
                    Consumer<LlmModelCatalogResult> sink);
}
