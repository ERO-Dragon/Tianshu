package com.rheinmetal.tianshu.function.weaving.settings;

import java.util.List;

/**
 * The providers this build ships an endpoint profile for.
 *
 * <p>One entry is one <em>mode</em> of the settings form: choosing a built-in provider means the player picks an
 * id here and the endpoint, the model id and the context window are taken from the profile instead of
 * being typed. That is what makes the shipped profile — not the config file — the definition of a
 * built-in provider, and it is why an entry that is exactly a profile can be recognised again
 * ({@link Provider#isProfileOf(LlmModelEntry)}) when it is edited.
 *
 * <p>The label is a language key rather than text on purpose: a built-in provider's name is player
 * facing, so it lives in the language files, and the stored {@code providerId} stays a stable,
 * untranslated identifier.
 *
 * <p>Endpoint data is a snapshot of the provider's public API, not a promise: a player who needs a
 * proxy or a different model uses the custom-API mode, which is a mode of the same form rather than a second
 * code path.
 */
public final class LlmBuiltinProviders {
    /** DeepSeek's OpenAI-compatible endpoint; also the defaults of the legacy single-model keys. */
    private static final Provider DEEPSEEK = new Provider(
            LlmConfiguration.DEFAULT_LLM_PROVIDER_ID,
            "tianshu.gui.llm.provider.deepseek",
            LlmConfiguration.DEFAULT_LLM_REMOTE_BASE_URL,
            LlmConfiguration.DEFAULT_LLM_REMOTE_MODEL,
            LlmConfiguration.DEFAULT_LLM_CONTEXT_WINDOW_TOKENS);

    /** Every built-in provider, in the order the form lists them; the first one is the default. */
    private static final List<Provider> PROVIDERS = List.of(DEEPSEEK);

    private LlmBuiltinProviders() {
    }

    /** All built-in providers, immutable. */
    public static List<Provider> all() {
        return PROVIDERS;
    }

    /** Ids of all built-in providers, in form order. */
    public static List<String> providerIds() {
        return PROVIDERS.stream().map(Provider::id).toList();
    }

    /** The provider a new entry starts from. */
    public static Provider defaultProvider() {
        return PROVIDERS.get(0);
    }

    /**
     * The built-in provider of one id.
     *
     * @return {@code null} when the id is not shipped by this build — that is exactly the test for the
     *         custom (unshipped) badge, so a new provider id becomes custom without any code change
     */
    public static Provider find(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return null;
        }
        String trimmed = providerId.trim();
        for (Provider provider : PROVIDERS) {
            if (provider.id().equals(trimmed)) {
                return provider;
            }
        }
        return null;
    }

    /** Whether this build ships a profile for the id. */
    public static boolean isBuiltin(String providerId) {
        return find(providerId) != null;
    }

    /**
     * One shipped provider profile.
     *
     * @param id                 stable identifier stored as {@code providerId}; never translated
     * @param labelKey           language key of the player-facing name
     * @param baseUrl            endpoint the profile talks to
     * @param defaultModelId     provider model id the profile uses
     * @param contextWindowTokens declared context window of that model
     */
    public record Provider(
            String id,
            String labelKey,
            String baseUrl,
            String defaultModelId,
            int contextWindowTokens
    ) {
        public Provider {
            id = requireText(id, LlmConfiguration.DEFAULT_LLM_PROVIDER_ID);
            labelKey = requireText(labelKey, "");
            baseUrl = requireText(baseUrl, LlmConfiguration.DEFAULT_LLM_REMOTE_BASE_URL);
            defaultModelId = requireText(defaultModelId, LlmConfiguration.DEFAULT_LLM_REMOTE_MODEL);
            contextWindowTokens = contextWindowTokens > 0
                    ? contextWindowTokens
                    : LlmConfiguration.DEFAULT_LLM_CONTEXT_WINDOW_TOKENS;
        }

        /**
         * Whether an entry is exactly this profile.
         *
         * <p>Used to decide which mode an existing entry opens in: a deviating endpoint, model id or
         * window is a custom configuration, and editing it in built-in mode would overwrite the
         * difference with the shipped defaults.
         */
        public boolean isProfileOf(LlmModelEntry entry) {
            return entry != null
                    && id.equals(entry.providerId())
                    && baseUrl.equals(entry.baseUrl())
                    && defaultModelId.equals(entry.modelId())
                    && contextWindowTokens == entry.contextWindowTokens();
        }

        /**
         * The model catalog this profile ships: exactly one model, the one the profile documents.
         *
         * <p>This is what the "restore default models" action restores and what the settings page
         * compares the configured catalog with, so "default" has one definition instead of a second
         * guess in the UI layer.
         */
        public List<LlmModelSpec> defaultCatalog() {
            return List.of(new LlmModelSpec(defaultModelId, defaultModelId, contextWindowTokens));
        }

        /** Whether a model catalog is exactly {@link #defaultCatalog()}. */
        public boolean isDefaultCatalog(List<LlmModelSpec> models) {
            return models != null && defaultCatalog().equals(models);
        }

        private static String requireText(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
