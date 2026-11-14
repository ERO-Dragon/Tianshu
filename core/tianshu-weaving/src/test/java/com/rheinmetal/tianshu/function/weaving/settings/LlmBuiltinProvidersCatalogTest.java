package com.rheinmetal.tianshu.function.weaving.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog file is data that ships with the build, so the loader refuses a profile the form could
 * not actually use. Each case here is one invariant the shipped resource is expected to satisfy; the
 * checks run against a string so a bad edit is caught by tests before it reaches a player.
 */
class LlmBuiltinProvidersCatalogTest {

    @Test
    void rejectsACatalogThatIsNotAnArray() {
        assertRejected("{\"providers\":[]}", "root must be an array");
    }

    @Test
    void rejectsAnEmptyCatalog() {
        assertRejected("[]", "at least one provider");
    }

    @Test
    void requiresDeepSeekFirstSoLegacyProfilesStillResolve() {
        assertRejected(catalog(provider("openai", "gpt-5.4", 400_000)), "first provider must be \"deepseek\"");
    }

    @Test
    void rejectsADuplicateProviderId() {
        assertRejected(catalog(provider("deepseek", "deepseek-flash", 102_400),
                provider("deepseek", "deepseek-flash", 102_400)), "duplicate provider id");
    }

    @Test
    void rejectsADefaultModelTheCatalogDoesNotList() {
        assertRejected(catalog(provider("deepseek", "missing-model", 102_400)), "does not list: missing-model");
    }

    @Test
    void rejectsAWindowBelowTheValidationFloor() {
        assertRejected(catalog(provider("deepseek", "deepseek-flash", 4096)), "outside the supported range");
    }

    @Test
    void rejectsAnUnknownReasoningWire() {
        assertRejected("""
                [{"id":"deepseek","labelKey":"tianshu.gui.llm.provider.deepseek",
                  "baseUrl":"https://api.deepseek.com/v1","defaultModelId":"deepseek-flash",
                  "reasoning":{"wire":"MAGIC","levels":[]},
                  "models":[{"modelId":"deepseek-flash","contextWindowTokens":102400}]}]
                """, "unknown value \"MAGIC\"");
    }

    @Test
    void rejectsLevelsForAProviderWithoutReasoningControl() {
        assertRejected("""
                [{"id":"deepseek","labelKey":"tianshu.gui.llm.provider.deepseek",
                  "baseUrl":"https://api.deepseek.com/v1","defaultModelId":"deepseek-flash",
                  "reasoning":{"wire":"NONE","levels":["low"]},
                  "models":[{"modelId":"deepseek-flash","contextWindowTokens":102400}]}]
                """, "levels for a wire that does not send them");
    }

    @Test
    void rejectsADefaultTierOutsideTheLadder() {
        assertRejected("""
                [{"id":"deepseek","labelKey":"tianshu.gui.llm.provider.deepseek",
                  "baseUrl":"https://api.deepseek.com/v1","defaultModelId":"deepseek-flash",
                  "reasoning":{"wire":"OPENAI_EFFORT","levels":["low","high"],"defaultTier":5},
                  "models":[{"modelId":"deepseek-flash","contextWindowTokens":102400}]}]
                """, "defaultTier is outside its ladder");
    }

    @Test
    void rejectsANonHttpEndpoint() {
        assertRejected("""
                [{"id":"deepseek","labelKey":"tianshu.gui.llm.provider.deepseek",
                  "baseUrl":"ftp://example.test","defaultModelId":"deepseek-flash",
                  "models":[{"modelId":"deepseek-flash","contextWindowTokens":102400}]}]
                """, "non-HTTP(S) baseUrl");
    }

    @Test
    void rejectsALabelThatIsNotALanguageKey() {
        assertRejected("""
                [{"id":"deepseek","labelKey":"DeepSeek","baseUrl":"https://api.deepseek.com/v1",
                  "defaultModelId":"deepseek-flash",
                  "models":[{"modelId":"deepseek-flash","contextWindowTokens":102400}]}]
                """, "labelKey outside");
    }

    @Test
    void rejectsAnUnknownCompletionField() {
        assertRejected("""
                [{"id":"deepseek","labelKey":"tianshu.gui.llm.provider.deepseek",
                  "baseUrl":"https://api.deepseek.com/v1","defaultModelId":"deepseek-flash",
                  "wireCompat":{"maxTokensField":"MAX_OUTPUT"},
                  "models":[{"modelId":"deepseek-flash","contextWindowTokens":102400}]}]
                """, "unknown value \"MAX_OUTPUT\"");
    }

    private static void assertRejected(String json, String message) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LlmBuiltinProviders.parseProviders(json));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    private static String catalog(String... providers) {
        return "[" + String.join(",", providers) + "]";
    }

    /** One provider object; a model id the test wants missing is left out of the catalog. */
    private static String provider(String id, String defaultModelId, int windowTokens) {
        String listedModel = defaultModelId.startsWith("missing") ? "listed-model" : defaultModelId;
        return """
                {"id":"%s","labelKey":"tianshu.gui.llm.provider.%s",
                 "baseUrl":"https://example.test/v1","defaultModelId":"%s",
                 "models":[{"modelId":"%s","contextWindowTokens":%d}]}
                """.formatted(id, id, defaultModelId, listedModel, windowTokens);
    }
}