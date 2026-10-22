package com.rheinmetal.tianshu.function.weaving.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Identity of one billable provider account: the endpoint that serves the request plus the
 * credential it is billed to.
 *
 * <p>The credential dimension is not decoration. One account may hold several API keys with
 * separate quotas and separate balances, so "the balance" is only meaningful per key. Every value
 * the account probe returns is therefore keyed by this record instead of being a single global
 * number.
 *
 * <p>{@link #apiKeyId()} is a one-way fingerprint, never the credential: the raw key must not be
 * stored in, or rendered from, a snapshot. The fingerprint is stable across restarts so a cached
 * value keeps belonging to the same key.
 */
public record LlmAccountKey(String providerId, String apiKeyId) {
    private static final String UNSPECIFIED = "";
    /** 12 hex characters: enough to keep two keys of one account apart, short enough to log. */
    private static final int FINGERPRINT_BYTES = 6;

    public LlmAccountKey {
        providerId = normalize(providerId);
        apiKeyId = normalize(apiKeyId);
    }

    /** Identity of "no account configured"; probe results for it are always unwired. */
    public static LlmAccountKey unspecified() {
        return new LlmAccountKey(UNSPECIFIED, UNSPECIFIED);
    }

    /**
     * Derives the identity from the configured endpoint and credential.
     *
     * @param providerId endpoint serving the requests; it is the provider dimension of the key
     * @param rawApiKey  bearer credential as configured; hashed here and never retained
     */
    public static LlmAccountKey of(String providerId, String rawApiKey) {
        String credential = rawApiKey == null ? "" : rawApiKey.trim();
        return new LlmAccountKey(providerId, credential.isEmpty() ? UNSPECIFIED : fingerprint(credential));
    }

    /** Whether an endpoint and a credential are both present, so a query would be meaningful. */
    public boolean configured() {
        return !providerId.isEmpty() && !apiKeyId.isEmpty();
    }

    private static String fingerprint(String credential) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(credential.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(FINGERPRINT_BYTES * 2);
            for (int i = 0; i < FINGERPRINT_BYTES; i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (Exception digestUnavailable) {
            // No digest implementation is impossible in practice; a weaker id beats a hard failure.
            return Integer.toHexString(credential.hashCode());
        }
    }

    private static String normalize(String value) {
        return value == null ? UNSPECIFIED : value.trim();
    }
}
