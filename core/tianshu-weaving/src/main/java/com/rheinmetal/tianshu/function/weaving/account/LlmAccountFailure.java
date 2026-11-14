package com.rheinmetal.tianshu.function.weaving.account;

/**
 * Why one account query produced no figures.
 *
 * <p>Each cause owns its player-facing language key and its diagnostic token. That is what lets the
 * balance row say "the key was rejected" instead of one generic failure, and lets a log line name the
 * cause without printing the credential, the endpoint's query string or any amount.
 *
 * <p>The keys live in the settings language files; this enum only names them, because the common layer
 * has no language files of its own.
 */
public enum LlmAccountFailure {
    /** The configured base URL is blank or not an http(s) address, so nothing was sent. */
    MISSING_ENDPOINT("tianshu.gui.llm.balance.missing_endpoint", "missing_endpoint"),
    /** No credential is configured for the account, so nothing was sent. */
    MISSING_CREDENTIAL("tianshu.gui.llm.balance.missing_credential", "missing_credential"),
    /** The credential cannot be a request header value, so nothing was sent. */
    CREDENTIAL_NOT_SENDABLE("tianshu.gui.llm.balance.credential_not_sendable", "credential_not_sendable"),
    /** The provider rejected the credential (HTTP 401 / 403). */
    UNAUTHORIZED("tianshu.gui.llm.balance.unauthorized", "unauthorized"),
    /** The provider answered with any other non-success status. */
    HTTP_ERROR("tianshu.gui.llm.balance.http_error", "http_error"),
    /** The answer was not the documented balance document. */
    MALFORMED_RESPONSE("tianshu.gui.llm.balance.malformed", "malformed_response"),
    /** The request never produced an answer: DNS, proxy, TLS or timeout. */
    NETWORK_ERROR("tianshu.gui.llm.balance.network_error", "network_error");

    private final String messageKey;
    private final String token;

    LlmAccountFailure(String messageKey, String token) {
        this.messageKey = messageKey;
        this.token = token;
    }

    /** Language key of this cause, written into the snapshot as its failure key. */
    public String messageKey() {
        return messageKey;
    }

    /** Stable token for diagnostics; never translated and never derived from the credential. */
    public String token() {
        return token;
    }

    /** Whether this cause carries an HTTP status in the diagnostic line. */
    public boolean quotesHttpStatus() {
        return this == UNAUTHORIZED || this == HTTP_ERROR;
    }
}
