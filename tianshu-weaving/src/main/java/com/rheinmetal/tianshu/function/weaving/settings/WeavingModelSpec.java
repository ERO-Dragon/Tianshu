package com.rheinmetal.tianshu.function.weaving.settings;

/**
 * One model a configured provider serves.
 *
 * <p>A provider entry owns a list of these and names one of them as its active model, so the same
 * endpoint and credential can serve several model ids without the player re-entering either. The
 * declared context window belongs to the model, not to the provider: the provider's price and window
 * tables list a window per model, and one endpoint can front a 51K and a 200K model at once.
 *
 * <p>The window is not typed into the settings page; it comes from a preset, which is why this value
 * keeps it as a plain token count with the shipped default for anything unset.
 *
 * <p>Like {@link WeavingModelEntry}, this value never carries the credential. It is serialised into the
 * model list, rendered in the settings page and may reach a log line, so only non-secret identity and
 * capability data live here.
 *
 * @param modelId             provider-side model id sent in the request body; never translated
 * @param displayName         name the settings page shows; defaults to the model id
 * @param contextWindowTokens declared context window, in tokens
 */
public record WeavingModelSpec(
        String modelId,
        String displayName,
        int contextWindowTokens
) {
    public WeavingModelSpec {
        modelId = requireText(modelId, WeavingConfiguration.DEFAULT_LLM_REMOTE_MODEL);
        displayName = requireText(displayName, modelId);
        contextWindowTokens = contextWindowTokens > 0
                ? contextWindowTokens
                : WeavingConfiguration.DEFAULT_LLM_CONTEXT_WINDOW_TOKENS;
    }

    /** A model that is named but not configured further: the id is its own display name. */
    public static WeavingModelSpec of(String modelId) {
        return new WeavingModelSpec(modelId, null, 0);
    }

    /** A model with an explicit window; the id is its own display name. */
    public static WeavingModelSpec of(String modelId, int contextWindowTokens) {
        return new WeavingModelSpec(modelId, null, contextWindowTokens);
    }

    /** Same model under another provider id. */
    public WeavingModelSpec withModelId(String modelId) {
        return new WeavingModelSpec(modelId, displayName, contextWindowTokens);
    }

    /** Same model labelled differently in the settings page. */
    public WeavingModelSpec withDisplayName(String displayName) {
        return new WeavingModelSpec(modelId, displayName, contextWindowTokens);
    }

    /** Same model declaring a different context window. */
    public WeavingModelSpec withContextWindowTokens(int contextWindowTokens) {
        return new WeavingModelSpec(modelId, displayName, contextWindowTokens);
    }

    private static String requireText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
