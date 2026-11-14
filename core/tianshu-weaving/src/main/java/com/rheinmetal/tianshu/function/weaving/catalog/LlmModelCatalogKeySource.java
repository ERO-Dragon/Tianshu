package com.rheinmetal.tianshu.function.weaving.catalog;

/**
 * Where the credential of one catalog request came from.
 *
 * <p>The settings page asks a provider for its model list before the player has saved anything, so the
 * credential it sends may be a value typed into the open form, one that was applied to the form but not
 * written yet, or the one already stored for the provider. When such a request fails the player has to
 * be told what was tried without the key ever being read back, so the origin travels with the request
 * and appears in the diagnostic line while the value itself does not.
 */
public enum LlmModelCatalogKeySource {
    /** Typed into the open provider form and not applied yet; spent as-is so a fetch works before saving. */
    TYPED("typed"),
    /** Applied to the provider form but not saved; the settings page holds it until its save writes it. */
    PENDING("pending"),
    /** Read from the stored configuration of the provider. */
    STORED("stored"),
    /** No credential is available; the request is still sent and the provider's answer decides. */
    NONE("none");

    private final String label;

    LlmModelCatalogKeySource(String label) {
        this.label = label;
    }

    /** Stable token used in diagnostics; never translated and never derived from the key itself. */
    public String label() {
        return label;
    }
}
