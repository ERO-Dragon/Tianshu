package com.rheinmetal.tianshu.function.weaving.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The providers this build ships an endpoint profile for.
 *
 * <p>One entry is one <em>mode</em> of the settings form: choosing a built-in provider means the player picks an
 * id here and the endpoint, the model ids and the context windows are taken from the profile instead of
 * being typed. That is what makes the shipped profile — not the config file — the definition of a
 * built-in provider, and it is why an entry that is exactly a profile can be recognised again
 * ({@link Provider#isProfileOf(WeavingModelEntry)}) when it is edited.
 *
 * <p>The profiles are data ({@code llm_providers.json} in the catalog resources), not Java constants:
 * an upstream rename is a data edit, and the same file can later be replaced by a refresh instead of a
 * code change. Labels stay language keys rather than text, so no player-facing string is hardcoded in
 * the data file either.</p>
 *
 * <p>Loading is strict on purpose. A malformed profile (two providers with one id, a default model the
 * catalog does not list, a window below the validation floor) fails when this class is first touched
 * instead of silently opening the form on something that cannot be requested. The checks live in
 * {@link #parseProviders(String)} so a test can exercise them against a string, not only the bundled
 * resource.</p>
 *
 * <p>Endpoint data is a snapshot of the provider's public API, not a promise: a player who needs a
 * proxy or a different model uses the custom-API mode, which is a mode of the same form rather than a second
 * code path. The catalog is a starting point, not a limit: the model picker can fetch the live list
 * from the endpoint once a credential is present.</p>
 */
public final class LlmBuiltinProviders {
    /** The provider catalog ships beside its owner; it is preset data, not a model catalog. */
    private static final String CATALOG_RESOURCE =
            "/com/rheinmetal/tianshu/function/weaving/settings/llm_providers.json";
    private static final String LABEL_PREFIX = "tianshu.gui.llm.provider.";

    /**
     * Loaded once, in file order: the first provider is the default one and the fallback for profiles
     * that predate the model list. DeepSeek has to stay first — the loader enforces it.
     */
    private static final List<Provider> PROVIDERS = loadProviders();

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

    // --- loading ----------------------------------------------------------------------------------

    private static List<Provider> loadProviders() {
        try (InputStream input = LlmBuiltinProviders.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing catalog resource " + CATALOG_RESOURCE);
            }
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonElement root;
                try {
                    root = JsonParser.parseReader(reader);
                } catch (RuntimeException malformed) {
                    throw invalid("catalog is not valid JSON", malformed);
                }
                return parseProviders(root, CATALOG_RESOURCE);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read " + CATALOG_RESOURCE, failure);
        }
    }

    /**
     * Parses a provider catalog. Package-private because it is the seam the tests validate malformed
     * data against; production only calls the resource overload.
     */
    static List<Provider> parseProviders(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException malformed) {
            throw invalid("catalog is not valid JSON", malformed);
        }
        return parseProviders(root, "test catalog");
    }

    private static List<Provider> parseProviders(JsonElement root, String source) {
        if (root == null || !root.isJsonArray()) {
            throw invalid(source + ": root must be an array of providers");
        }
        List<Provider> providers = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : root.getAsJsonArray()) {
            Provider provider = parseProvider(element, source);
            if (!ids.add(provider.id())) {
                throw invalid(source + ": duplicate provider id \"" + provider.id() + "\"");
            }
            providers.add(provider);
        }
        if (providers.isEmpty()) {
            throw invalid(source + ": at least one provider is required");
        }
        if (!providers.get(0).id().equals(WeavingConfiguration.DEFAULT_LLM_PROVIDER_ID)) {
            throw invalid(source + ": the first provider must be \"" + WeavingConfiguration.DEFAULT_LLM_PROVIDER_ID
                    + "\" because it is the fallback for legacy profiles");
        }
        return List.copyOf(providers);
    }

    private static Provider parseProvider(JsonElement element, String source) {
        if (element == null || !element.isJsonObject()) {
            throw invalid(source + ": every provider must be an object");
        }
        JsonObject object = element.getAsJsonObject();
        String id = requiredString(object, "id", source);
        String labelKey = requiredString(object, "labelKey", source);
        if (!labelKey.startsWith(LABEL_PREFIX)) {
            throw invalid(source + ": provider \"" + id + "\" has a labelKey outside " + LABEL_PREFIX);
        }
        String baseUrl = requiredString(object, "baseUrl", source);
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw invalid(source + ": provider \"" + id + "\" has a non-HTTP(S) baseUrl");
        }
        String defaultModelId = requiredString(object, "defaultModelId", source);
        List<WeavingModelSpec> models = parseModels(object, id, source);
        WeavingModelSpec defaultModel = models.stream()
                .filter(spec -> spec.modelId().equals(defaultModelId))
                .findFirst()
                .orElseThrow(() -> invalid(source + ": provider \"" + id
                        + "\" defaults to a model its catalog does not list: " + defaultModelId));
        LlmReasoningProfile reasoning = parseReasoning(object, id, source);
        LlmWireCompat wireCompat = parseWireCompat(object, id, source);
        return new Provider(id, labelKey, baseUrl, defaultModelId, defaultModel.contextWindowTokens(),
                reasoning, wireCompat, models);
    }

    private static List<WeavingModelSpec> parseModels(JsonObject provider, String providerId, String source) {
        JsonElement element = provider.get("models");
        if (element == null || !element.isJsonArray()) {
            throw invalid(source + ": provider \"" + providerId + "\" must declare a models array");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) {
            throw invalid(source + ": provider \"" + providerId + "\" must list at least one model");
        }
        List<WeavingModelSpec> models = new ArrayList<>(array.size());
        Set<String> modelIds = new HashSet<>();
        for (JsonElement modelElement : array) {
            if (modelElement == null || !modelElement.isJsonObject()) {
                throw invalid(source + ": provider \"" + providerId + "\" has a non-object model entry");
            }
            JsonObject model = modelElement.getAsJsonObject();
            String modelId = requiredString(model, "modelId", source);
            if (!modelIds.add(modelId)) {
                throw invalid(source + ": provider \"" + providerId + "\" lists model \"" + modelId + "\" twice");
            }
            String displayName = optionalString(model, "displayName", source);
            int windowTokens = requiredInt(model, "contextWindowTokens", source);
            if (windowTokens < WeavingContextTiers.minimumTier()
                    || windowTokens > WeavingConfiguration.MAX_LLM_CONTEXT_WINDOW_TOKENS) {
                throw invalid(source + ": provider \"" + providerId + "\" model \"" + modelId
                        + "\" declares a window outside the supported range");
            }
            models.add(new WeavingModelSpec(modelId, displayName, windowTokens));
        }
        return models;
    }

    private static LlmReasoningProfile parseReasoning(JsonObject provider, String providerId, String source) {
        JsonElement element = provider.get("reasoning");
        if (element == null || element.isJsonNull()) {
            return LlmReasoningProfile.NONE;
        }
        if (!element.isJsonObject()) {
            throw invalid(source + ": provider \"" + providerId + "\" has a non-object reasoning profile");
        }
        JsonObject reasoning = element.getAsJsonObject();
        String wireText = requiredString(reasoning, "wire", source);
        LlmReasoningWire wire = enumValue(LlmReasoningWire.class, wireText, source,
                "provider \"" + providerId + "\" reasoning.wire");
        boolean includesNoThinking = optionalBoolean(reasoning, "includesNoThinking", false, source);
        List<String> levels = optionalStringArray(reasoning, "levels", source);
        int defaultTier = optionalInt(reasoning, "defaultTier", 0, source);
        LlmReasoningProfile profile = new LlmReasoningProfile(wire, includesNoThinking, levels, defaultTier);
        if (wire == LlmReasoningWire.NONE && (includesNoThinking || !levels.isEmpty())) {
            throw invalid(source + ": provider \"" + providerId
                    + "\" declares levels for a wire that does not send them");
        }
        int tierCount = profile.tierCount();
        if (tierCount > 0 && (defaultTier < 0 || defaultTier >= tierCount)) {
            throw invalid(source + ": provider \"" + providerId + "\" reasoning.defaultTier is outside its ladder");
        }
        return profile;
    }

    private static LlmWireCompat parseWireCompat(JsonObject provider, String providerId, String source) {
        JsonElement element = provider.get("wireCompat");
        if (element == null || element.isJsonNull()) {
            return LlmWireCompat.DEFAULT;
        }
        if (!element.isJsonObject()) {
            throw invalid(source + ": provider \"" + providerId + "\" has a non-object wireCompat");
        }
        JsonObject compat = element.getAsJsonObject();
        boolean includeStreamUsage = optionalBoolean(compat, "includeStreamUsage", true, source);
        String fieldText = optionalString(compat, "maxTokensField", source);
        LlmWireCompat.MaxTokensField field = fieldText == null
                ? LlmWireCompat.MaxTokensField.MAX_TOKENS
                : enumValue(LlmWireCompat.MaxTokensField.class, fieldText, source,
                        "provider \"" + providerId + "\" wireCompat.maxTokensField");
        return new LlmWireCompat(includeStreamUsage, field);
    }

    // --- field helpers ----------------------------------------------------------------------------

    private static String requiredString(JsonObject object, String field, String source) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw invalid(source + ": missing or blank \"" + field + "\"");
        }
        return value.getAsString().trim();
    }

    private static String optionalString(JsonObject object, String field, String source) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(source + ": \"" + field + "\" must be a string");
        }
        String text = value.getAsString().trim();
        return text.isEmpty() ? null : text;
    }

    private static int requiredInt(JsonObject object, String field, String source) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(source + ": missing numeric \"" + field + "\"");
        }
        return value.getAsInt();
    }

    private static int optionalInt(JsonObject object, String field, int fallback, String source) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(source + ": \"" + field + "\" must be a number");
        }
        return value.getAsInt();
    }

    private static boolean optionalBoolean(JsonObject object, String field, boolean fallback, String source) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid(source + ": \"" + field + "\" must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static List<String> optionalStringArray(JsonObject object, String field, String source) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (!value.isJsonArray()) {
            throw invalid(source + ": \"" + field + "\" must be an array of strings");
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw invalid(source + ": \"" + field + "\" must be an array of strings");
            }
            values.add(element.getAsString());
        }
        return values;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String text, String source, String what) {
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException unknown) {
            throw invalid(source + ": " + what + " has an unknown value \"" + text + "\"");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("llm_providers.json: " + message);
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException("llm_providers.json: " + message, cause);
    }

    /**
     * One shipped provider profile.
     *
     * @param id                 stable identifier stored as {@code providerId}; never translated
     * @param labelKey           language key of the player-facing name
     * @param baseUrl            endpoint the profile talks to
     * @param defaultModelId     provider model id the profile starts on
     * @param contextWindowTokens declared context window of that model
     * @param reasoning          the reasoning ladder this endpoint offers
     * @param wireCompat         the optional request fields this endpoint accepts
     * @param catalog            the models the form starts with; non-empty and always contains the default
     */
    public record Provider(
            String id,
            String labelKey,
            String baseUrl,
            String defaultModelId,
            int contextWindowTokens,
            LlmReasoningProfile reasoning,
            LlmWireCompat wireCompat,
            List<WeavingModelSpec> catalog
    ) {
        public Provider {
            id = requireText(id, WeavingConfiguration.DEFAULT_LLM_PROVIDER_ID);
            labelKey = requireText(labelKey, "");
            baseUrl = requireText(baseUrl, WeavingConfiguration.DEFAULT_LLM_REMOTE_BASE_URL);
            defaultModelId = requireText(defaultModelId, WeavingConfiguration.DEFAULT_LLM_REMOTE_MODEL);
            contextWindowTokens = contextWindowTokens > 0
                    ? contextWindowTokens
                    : WeavingConfiguration.DEFAULT_LLM_CONTEXT_WINDOW_TOKENS;
            reasoning = reasoning == null ? LlmReasoningProfile.NONE : reasoning;
            wireCompat = wireCompat == null ? LlmWireCompat.DEFAULT : wireCompat;
            catalog = normalizeCatalog(catalog, defaultModelId, contextWindowTokens);
        }

        /**
         * Whether an entry is exactly this profile.
         *
         * <p>Used to decide which mode an existing entry opens in: a deviating endpoint, model id or
         * window is a custom configuration, and editing it in built-in mode would overwrite the
         * difference with the shipped defaults.</p>
         */
        public boolean isProfileOf(WeavingModelEntry entry) {
            return entry != null
                    && id.equals(entry.providerId())
                    && baseUrl.equals(entry.baseUrl())
                    && defaultModelId.equals(entry.modelId())
                    && contextWindowTokens == entry.contextWindowTokens();
        }

        /**
         * The model catalog this profile ships.
         *
         * <p>This is what the "restore default models" action restores and what the settings page
         * compares the configured catalog with, so "default" has one definition instead of a second
         * guess in the UI layer. It is always non-empty and always contains the default model.</p>
         */
        public List<WeavingModelSpec> defaultCatalog() {
            return catalog;
        }

        /** Whether a model catalog is exactly {@link #defaultCatalog()}. */
        public boolean isDefaultCatalog(List<WeavingModelSpec> models) {
            return models != null && defaultCatalog().equals(models);
        }

        /**
         * The declared window of one catalog model, or {@code fallback} when this profile does not
         * list it.
         *
         * <p>The model picker answers with ids only, so this is what keeps a model that is already in
         * the preset catalog on its real window when the live list arrives.</p>
         */
        public int contextWindowFor(String modelId, int fallback) {
            if (modelId != null) {
                String trimmed = modelId.trim();
                for (WeavingModelSpec spec : catalog) {
                    if (spec.modelId().equals(trimmed)) {
                        return spec.contextWindowTokens();
                    }
                }
            }
            return fallback;
        }

        /**
         * Keeps the default model inside the catalog: a profile whose data drifted must still open on a
         * model it can request, rather than on an id its own form does not list.
         */
        private static List<WeavingModelSpec> normalizeCatalog(
                List<WeavingModelSpec> catalog,
                String defaultModelId,
                int contextWindowTokens
        ) {
            List<WeavingModelSpec> normalized = new ArrayList<>();
            if (catalog != null) {
                for (WeavingModelSpec spec : catalog) {
                    if (spec != null) {
                        normalized.add(spec);
                    }
                }
            }
            if (normalized.isEmpty()) {
                normalized.add(new WeavingModelSpec(defaultModelId, defaultModelId, contextWindowTokens));
            }
            boolean defaultPresent = normalized.stream()
                    .anyMatch(spec -> spec.modelId().equals(defaultModelId));
            if (!defaultPresent) {
                normalized.add(0, new WeavingModelSpec(defaultModelId, defaultModelId, contextWindowTokens));
            }
            return List.copyOf(normalized);
        }

        private static String requireText(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}