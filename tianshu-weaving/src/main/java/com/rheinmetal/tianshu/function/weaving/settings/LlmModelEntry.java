package com.rheinmetal.tianshu.function.weaving.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One configured provider: where it is, and which of its models the runtime uses.
 *
 * <p>A provider serves several models, so this value carries a {@link #models()} list and names one of
 * them as {@link #activeModelId()}. The single-model accessors {@link #modelId()} and
 * {@link #contextWindowTokens()} keep reporting the <em>active</em> model, which is why every caller
 * written against the old single-model entry (the chat path, the runtime inspector, the account key)
 * keeps working unchanged while the list grows underneath it.
 *
 * <p>The credential is deliberately <em>not</em> part of this value: entries get serialised, logged
 * and rendered, so the API key is stored separately and looked up by {@link #id()}.
 */
public record LlmModelEntry(
        String id,
        String displayName,
        String providerId,
        String baseUrl,
        List<LlmModelSpec> models,
        String activeModelId
) {
    public LlmModelEntry {
        id = requireText(id, UNNAMED_ID);
        displayName = requireText(displayName, id);
        providerId = requireText(providerId, CUSTOM_PROVIDER_ID);
        baseUrl = requireText(baseUrl, LlmConfiguration.DEFAULT_LLM_REMOTE_BASE_URL);
        models = sanitizeModels(models);
        activeModelId = resolveActiveModelId(models, activeModelId);
    }

    /**
     * A provider with exactly one model, which is also its active one. This is the shape the legacy
     * single-model keys resolve to, and the shape every old config file migrates into.
     */
    public LlmModelEntry(
            String id,
            String displayName,
            String providerId,
            String baseUrl,
            String modelId,
            int contextWindowTokens
    ) {
        this(id, displayName, providerId, baseUrl,
                List.of(new LlmModelSpec(modelId, modelId, contextWindowTokens)), null);
    }

    /** The entry synthesised from the legacy single-model keys, used for migration. */
    public static LlmModelEntry legacy(
            String baseUrl,
            String modelId,
            int contextWindowTokens
    ) {
        return new LlmModelEntry(LEGACY_ID, modelId, LlmConfiguration.DEFAULT_LLM_PROVIDER_ID, baseUrl, modelId,
                contextWindowTokens);
    }

    /** Stable id of the entry that the legacy single-model keys migrate into. */
    public static final String LEGACY_ID = "default";

    /** Stand-in id for a value built without one; the persisted form rejects such an entry. */
    public static final String UNNAMED_ID = "unnamed";

    /** Provider id of an entry that does not name the provider it talks to. */
    public static final String CUSTOM_PROVIDER_ID = "custom";

    /**
     * The model this provider currently uses. Always one of {@link #models()}: an unknown or missing
     * selection resolves to the first model, so no caller has to handle "no active model".
     */
    public LlmModelSpec activeModel() {
        String active = activeModelId;
        for (LlmModelSpec spec : models) {
            if (spec.modelId().equals(active)) {
                return spec;
            }
        }
        return models.get(0);
    }

    /**
     * Provider model id of the active model. This is the accessor the request path reads, so it reports
     * the selection rather than a fixed first entry.
     */
    public String modelId() {
        return activeModel().modelId();
    }

    /** Declared context window of the active model, in tokens. */
    public int contextWindowTokens() {
        return activeModel().contextWindowTokens();
    }

    /** Same provider, another endpoint. */
    public LlmModelEntry withBaseUrl(String baseUrl) {
        return new LlmModelEntry(id, displayName, providerId, baseUrl, models, activeModelId);
    }

    /** Same provider pointing at another of its models. */
    public LlmModelEntry withActiveModelId(String activeModelId) {
        return new LlmModelEntry(id, displayName, providerId, baseUrl, models, activeModelId);
    }

    /**
     * Same provider whose active model is now addressed by another id.
     *
     * <p>A model list entry with that id wins: pointing at an existing model must switch to it instead
     * of renaming the current one over it. Otherwise the active model is renamed, and a display name
     * that was simply the old id follows the rename so the page cannot show a stale label.
     */
    public LlmModelEntry withModelId(String modelId) {
        String next = requireText(modelId, LlmConfiguration.DEFAULT_LLM_REMOTE_MODEL);
        for (LlmModelSpec spec : models) {
            if (spec.modelId().equals(next)) {
                return withActiveModelId(next);
            }
        }
        List<LlmModelSpec> nextModels = new ArrayList<>(models);
        LlmModelSpec active = activeModel();
        int index = nextModels.indexOf(active);
        String nextDisplayName = active.displayName().equals(active.modelId())
                ? next
                : active.displayName();
        nextModels.set(index, active.withModelId(next).withDisplayName(nextDisplayName));
        return new LlmModelEntry(id, displayName, providerId, baseUrl, nextModels, next);
    }

    /** Same provider whose active model declares a different context window. */
    public LlmModelEntry withContextWindowTokens(int contextWindowTokens) {
        List<LlmModelSpec> nextModels = new ArrayList<>(models);
        LlmModelSpec active = activeModel();
        nextModels.set(nextModels.indexOf(active), active.withContextWindowTokens(contextWindowTokens));
        return new LlmModelEntry(id, displayName, providerId, baseUrl, nextModels, activeModelId);
    }

    /**
     * A provider needs at least one model to be a usable endpoint, so an empty or missing list becomes
     * exactly one model built from the shipped default. That keeps {@link #modelId()} total and lets a
     * corrupt list degrade to a usable endpoint instead of an entry without a model.
     *
     * <p>One model per id: the first occurrence wins, so a stale duplicate cannot shadow a live model.
     */
    private static List<LlmModelSpec> sanitizeModels(List<LlmModelSpec> models) {
        if (models == null || models.isEmpty()) {
            return List.of(LlmModelSpec.of(LlmConfiguration.DEFAULT_LLM_REMOTE_MODEL));
        }
        Map<String, LlmModelSpec> unique = new LinkedHashMap<>();
        for (LlmModelSpec spec : models) {
            if (spec != null) {
                unique.putIfAbsent(spec.modelId(), spec);
            }
        }
        return unique.isEmpty()
                ? List.of(LlmModelSpec.of(LlmConfiguration.DEFAULT_LLM_REMOTE_MODEL))
                : List.copyOf(unique.values());
    }

    private static String resolveActiveModelId(List<LlmModelSpec> models, String activeModelId) {
        if (activeModelId != null && !activeModelId.isBlank()) {
            String trimmed = activeModelId.trim();
            for (LlmModelSpec spec : models) {
                if (spec.modelId().equals(trimmed)) {
                    return trimmed;
                }
            }
        }
        return models.get(0).modelId();
    }

    private static String requireText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
