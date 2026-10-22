package com.rheinmetal.tianshu.function.llm.catalog;

import java.util.ArrayList;
import java.util.List;

/**
 * One answer of a provider's model-list endpoint.
 *
 * <p>Model ids only: this result never changes any configuration by itself, so a fetch can only fill
 * a form that the player still has to save. That is what keeps "ask the provider what it serves" and
 * "write the configuration" two separate decisions.
 *
 * @param state    what happened; see {@link LlmModelCatalogState}
 * @param modelIds provider model ids in the order the provider listed them, empty unless
 *                 {@link LlmModelCatalogState#READY}
 * @param detail   diagnostic detail for a failure (status code, exception message, ...); never a
 *                 credential, and never shown as the primary message
 */
public record LlmModelCatalogResult(
        LlmModelCatalogState state,
        List<String> modelIds,
        String detail
) {
    public LlmModelCatalogResult {
        state = state == null ? LlmModelCatalogState.NOT_WIRED : state;
        modelIds = state.success() ? normalize(modelIds) : List.of();
        detail = detail == null ? "" : detail.trim();
    }

    /**
     * A successful answer.
     *
     * @return {@link LlmModelCatalogState#READY} when the provider listed at least one model,
     *         {@link LlmModelCatalogState#EMPTY} otherwise
     */
    public static LlmModelCatalogResult success(List<String> modelIds) {
        List<String> normalized = normalize(modelIds);
        return normalized.isEmpty()
                ? failure(LlmModelCatalogState.EMPTY, "")
                : new LlmModelCatalogResult(LlmModelCatalogState.READY, normalized, "");
    }

    /** A failed answer; the state carries the language key the page shows. */
    public static LlmModelCatalogResult failure(LlmModelCatalogState state, String detail) {
        return new LlmModelCatalogResult(state, List.of(), detail);
    }

    /** No client is bound, so no request was made. */
    public static LlmModelCatalogResult notWired() {
        return failure(LlmModelCatalogState.NOT_WIRED, "");
    }

    public boolean success() {
        return state.success();
    }

    /** Language key of this answer, empty on success. */
    public String messageKey() {
        return state.messageKey();
    }

    /** Stable order, no blanks, one entry per id: a provider may repeat or pad its list. */
    private static List<String> normalize(List<String> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return List.of();
        }
        List<String> unique = new ArrayList<>(modelIds.size());
        for (String modelId : modelIds) {
            if (modelId == null || modelId.isBlank()) {
                continue;
            }
            String trimmed = modelId.trim();
            if (!unique.contains(trimmed)) {
                unique.add(trimmed);
            }
        }
        return List.copyOf(unique);
    }

    /** Ids only: this record may reach a log line and the detail must not be the whole body. */
    @Override
    public String toString() {
        return "LlmModelCatalogResult{state=" + state
                + ", modelIds=" + modelIds.size()
                + (detail.isEmpty() ? "" : ", detail=" + detail)
                + "}";
    }
}
