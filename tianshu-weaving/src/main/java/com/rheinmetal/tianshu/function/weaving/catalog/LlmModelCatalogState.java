package com.rheinmetal.tianshu.function.weaving.catalog;

/**
 * Outcome of one {@code GET {baseUrl}/models} request.
 *
 * <p>The state is the machine-readable half of an answer and the {@link #messageKey()} is the
 * player-facing half: the provider request happens in the common layer, which has no language files,
 * so it reports a resource key and the settings page renders it. Every failure state carries a key, so
 * there is no path that fails silently, and no two failures share one: "the credential was rejected",
 * "the endpoint never answered" and "the answer was not a model list" ask for different fixes.
 */
public enum LlmModelCatalogState {
    /** The provider answered with at least one model. */
    READY(""),
    /** The provider answered, but listed no model at all. */
    EMPTY("tianshu.gui.llm.catalog.empty"),
    /** No client is bound, so no request could be made. */
    NOT_WIRED("tianshu.gui.llm.catalog.not_wired"),
    /** The endpoint is blank or not an http(s) URL, so nothing was sent. */
    MISSING_ENDPOINT("tianshu.gui.llm.catalog.missing_endpoint"),
    /**
     * A credential was supplied but cannot be sent: it holds a line break or characters outside the
     * header range. Nothing was sent, because the transport would reject the value itself and quote it
     * back in its own error message.
     */
    CREDENTIAL_NOT_SENDABLE("tianshu.gui.llm.catalog.credential_not_sendable"),
    /** The credential was rejected (HTTP 401 / 403). The detail carries the status code. */
    UNAUTHORIZED("tianshu.gui.llm.catalog.unauthorized"),
    /** Any other non-success status. The detail carries the status code. */
    HTTP_ERROR("tianshu.gui.llm.catalog.http_error"),
    /** The answer parsed as JSON but not as a model list. */
    MALFORMED_RESPONSE("tianshu.gui.llm.catalog.malformed"),
    /** The request never produced an answer: DNS, proxy, TLS or timeout. */
    NETWORK_ERROR("tianshu.gui.llm.catalog.network_error");

    private final String messageKey;

    LlmModelCatalogState(String messageKey) {
        this.messageKey = messageKey;
    }

    /** Language key of this state, empty for {@link #READY}. */
    public String messageKey() {
        return messageKey;
    }

    /** Whether this state carries models. */
    public boolean success() {
        return this == READY;
    }

    /**
     * Whether a result's detail is an HTTP status this state's message may quote.
     *
     * <p>A status is a fact the provider stated, so the sentence can say "rejected with HTTP 401"
     * instead of "rejected". Everything else in a detail is a transport or parser note and stays in the
     * diagnostic line. The distinction has one definition here instead of a guess per call site.
     */
    public boolean quotesHttpStatus() {
        return this == UNAUTHORIZED || this == HTTP_ERROR;
    }

    /**
     * Whether this state's language text has a placeholder for the detail.
     *
     * <p>The transport note is the only actionable part of a network failure and the status is the only
     * actionable part of an HTTP failure, so both sentences quote it; the other failures quote nothing
     * because their detail is a parser or wiring note that would only confuse the player.
     */
    public boolean takesDetailArgument() {
        return quotesHttpStatus() || this == NETWORK_ERROR;
    }
}
