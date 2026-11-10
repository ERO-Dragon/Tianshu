package com.rheinmetal.tianshu.function.weaving.settings;

/**
 * The two optional request fields a relay station may or may not accept.
 *
 * <p>OpenAI's own endpoint takes both, which is why the defaults are the strict shape: ask for usage
 * inside the stream and send {@code max_tokens}. A gateway that predates either field answers 400
 * instead of ignoring it, so the entry has to be able to say "do not send that" — the same idea as a
 * reasoning ladder being provider data rather than a hardcoded guess.</p>
 *
 * <p>Field names follow the wire, not this build: {@code max_tokens} is the long-standing name,
 * {@code max_completion_tokens} the one newer OpenAI models document.</p>
 */
public record LlmWireCompat(boolean includeStreamUsage, MaxTokensField maxTokensField) {

    /** Which request field carries the completion limit. */
    public enum MaxTokensField {
        /** The long-standing field; accepted by most OpenAI-compatible gateways, including DeepSeek. */
        MAX_TOKENS("max_tokens"),
        /** What newer OpenAI models document; older relays reject it as unknown. */
        MAX_COMPLETION_TOKENS("max_completion_tokens");

        private final String wireName;

        MaxTokensField(String wireName) {
            this.wireName = wireName;
        }

        /** The JSON field name sent to the endpoint. */
        public String wireName() {
            return wireName;
        }
    }

    /** What this build sent before the switch existed: strict OpenAI shape on the established fields. */
    public static final LlmWireCompat DEFAULT = new LlmWireCompat(true, MaxTokensField.MAX_TOKENS);

    public LlmWireCompat {
        maxTokensField = maxTokensField == null ? MaxTokensField.MAX_TOKENS : maxTokensField;
    }

    // --- persistence ------------------------------------------------------------------------------
    /* Stored as one escaped field, like a credential: includeUsage;maxTokensField. */

    private static final char FIELD = ';';
    private static final int FIELD_COUNT = 2;

    public String encode() {
        return (includeStreamUsage ? '1' : '0') + String.valueOf(FIELD) + maxTokensField.name();
    }

    /** @return the decoded value, or {@code null} when the text is not one this build understands */
    public static LlmWireCompat decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] fields = encoded.split(String.valueOf(FIELD), -1);
        if (fields.length != FIELD_COUNT) {
            return null;
        }
        MaxTokensField field = parseField(fields[1]);
        if (field == null) {
            return null;
        }
        String includeUsage = fields[0].trim();
        if (!"0".equals(includeUsage) && !"1".equals(includeUsage)) {
            return null;
        }
        return new LlmWireCompat("1".equals(includeUsage), field);
    }

    private static MaxTokensField parseField(String raw) {
        String normalized = raw == null ? "" : raw.trim();
        for (MaxTokensField candidate : MaxTokensField.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}