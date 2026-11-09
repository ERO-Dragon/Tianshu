package com.rheinmetal.tianshu.function.weaving.settings;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped provider presets are data, so their invariants are pinned here: an endpoint, a default
 * model that the catalog actually contains, windows above the validation floor, and one stable id per
 * provider. A catalog that drifts away from its default would open the form on a model it cannot send.
 */
class LlmBuiltinProvidersTest {

    @Test
    void deepSeekStaysTheDefaultAndTheLegacyProfile() {
        LlmBuiltinProviders.Provider provider = LlmBuiltinProviders.defaultProvider();

        assertEquals(WeavingConfiguration.DEFAULT_LLM_PROVIDER_ID, provider.id());
        assertEquals(WeavingConfiguration.DEFAULT_LLM_REMOTE_MODEL, provider.defaultModelId());
        assertEquals(WeavingConfiguration.DEFAULT_LLM_CONTEXT_WINDOW_TOKENS,
                provider.defaultCatalog().get(0).contextWindowTokens(),
                "the shipped catalog must keep the legacy context window in step with the constant");
        assertEquals(LlmReasoningWire.DEEPSEEK_THINKING, provider.reasoning().wire());
        assertEquals(1, provider.reasoning().defaultTier(), "the shipped DeepSeek ladder starts on low");
        assertEquals(1, provider.defaultCatalog().size(), "DeepSeek keeps its single-model profile");
    }

    @Test
    void everyPresetIsRequestable() {
        Set<String> ids = new HashSet<>();
        for (LlmBuiltinProviders.Provider provider : LlmBuiltinProviders.all()) {
            assertTrue(ids.add(provider.id()), "duplicate provider id: " + provider.id());
            assertFalse(provider.id().isBlank());
            assertTrue(provider.labelKey().startsWith("tianshu.gui.llm.provider."),
                    "the label must be a language key: " + provider.labelKey());
            assertTrue(provider.baseUrl().startsWith("http://") || provider.baseUrl().startsWith("https://"),
                    "endpoint must be an HTTP(S) base: " + provider.baseUrl());
            assertTrue(provider.contextWindowTokens() >= WeavingContextTiers.minimumTier(),
                    provider.id() + " declares a window below the validation floor");

            List<WeavingModelSpec> catalog = provider.defaultCatalog();
            assertFalse(catalog.isEmpty(), provider.id() + " must ship at least one model");
            assertTrue(catalog.stream().anyMatch(spec -> spec.modelId().equals(provider.defaultModelId())),
                    provider.id() + " must list its default model");
            for (WeavingModelSpec spec : catalog) {
                assertTrue(spec.contextWindowTokens() >= WeavingContextTiers.minimumTier(),
                        provider.id() + "/" + spec.modelId() + " declares a window below the floor");
            }
        }
    }

    @Test
    void theMajorOfficialProvidersArePresent() {
        for (String id : List.of("deepseek", "openai", "xai", "google", "qwen", "moonshot", "zai")) {
            assertNotNull(LlmBuiltinProviders.find(id), "missing shipped provider: " + id);
        }
    }

    @Test
    void openAiAndGoogleCarryAReasoningLadderAndTheOthersDoNotGuessOne() {
        assertEquals(LlmReasoningWire.OPENAI_EFFORT, LlmBuiltinProviders.find("openai").reasoning().wire());
        assertEquals(LlmReasoningWire.OPENAI_EFFORT, LlmBuiltinProviders.find("google").reasoning().wire());
        assertEquals(LlmReasoningProfile.NONE, LlmBuiltinProviders.find("xai").reasoning());
        assertEquals(LlmReasoningProfile.NONE, LlmBuiltinProviders.find("qwen").reasoning());
        assertEquals(LlmReasoningProfile.NONE, LlmBuiltinProviders.find("moonshot").reasoning());
        assertEquals(LlmReasoningProfile.NONE, LlmBuiltinProviders.find("zai").reasoning());
    }

    @Test
    void theCompletionFieldFollowsTheProviderShape() {
        assertEquals(LlmWireCompat.MaxTokensField.MAX_COMPLETION_TOKENS,
                LlmBuiltinProviders.find("openai").wireCompat().maxTokensField());
        assertEquals(LlmWireCompat.MaxTokensField.MAX_COMPLETION_TOKENS,
                LlmBuiltinProviders.find("xai").wireCompat().maxTokensField());
        assertEquals(LlmWireCompat.MaxTokensField.MAX_TOKENS,
                LlmBuiltinProviders.find("deepseek").wireCompat().maxTokensField());
    }

    @Test
    void aPresetCatalogWithAMultiModelListOpensOnItsDefault() {
        LlmBuiltinProviders.Provider openai = LlmBuiltinProviders.find("openai");

        assertEquals("gpt-5.4", openai.defaultModelId());
        assertTrue(openai.defaultCatalog().size() > 1, "the OpenAI preset lists more than its default model");
        assertEquals(openai.defaultCatalog().get(0).modelId(), openai.defaultModelId(),
                "the default model is the one the form opens on");
    }
}