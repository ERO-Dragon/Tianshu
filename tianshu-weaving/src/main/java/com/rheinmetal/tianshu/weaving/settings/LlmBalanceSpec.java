package com.rheinmetal.tianshu.weaving.settings;

/**
 * Where the remaining balance of one entry is read from, and in which document shape.
 *
 * <p>Providers disagree on the balance API the same way they disagree on the request fields: some
 * publish a documented endpoint of their own, some only an OpenAI-style billing pair, and many publish
 * nothing at all. So the endpoint is data, not a code path per provider: the built-in profile carries
 * the default for its provider, and a custom endpoint may override it (the settings page stores the
 * override exactly like the reasoning ladder and the request-field compatibility).</p>
 *
 * <p>{@link BalanceWire#NONE} is a real answer: "this provider does not publish a balance", which the
 * page renders as such. It is not a failure and never becomes a zero.</p>
 *
 * @param wire shape of the answer, which also decides the default path and the currency
 * @param path endpoint path relative to the entry's base URL; empty means the wire's documented path
 */
public record LlmBalanceSpec(BalanceWire wire, String path) {

    /** The answer shapes this build can read. */
    public enum BalanceWire {
        /** No balance endpoint: the row reports that the provider does not publish one. */
        NONE,
        /** {@code {"balance_infos":[{"currency","total_balance",...}]}} — DeepSeek's documented shape. */
        DEEPSEEK,
        /** {@code {"data":{"available_balance":...}}} — Moonshot/Kimi's documented shape (USD). */
        MOONSHOT,
        /** The OpenAI dashboard pair: {@code /dashboard/billing/subscription} plus {@code .../usage}. */
        OPENAI_BILLING
    }

    /** The shipped default for a provider that publishes no balance API. */
    public static final LlmBalanceSpec NONE = new LlmBalanceSpec(BalanceWire.NONE, "");

    private static final int MAX_PATH_CHARS = 256;

    public LlmBalanceSpec {
        wire = wire == null ? BalanceWire.NONE : wire;
        path = path == null ? "" : path.trim();
    }

    /** Whether this entry has a balance the page can ask for. */
    public boolean supportsQuery() {
        return wire != BalanceWire.NONE;
    }

    /**
     * The path to request, with the wire's documented default filled in.
     *
     * <p>The defaults live here rather than in the JSON so a profile that states only the wire still
     * gets the documented path, and a relay that serves the same document elsewhere states a path.</p>
     */
    public String effectivePath() {
        if (!path.isEmpty()) {
            return path;
        }
        return switch (wire) {
            case DEEPSEEK -> "/user/balance";
            case MOONSHOT -> "/users/me/balance";
            case OPENAI_BILLING -> "/dashboard/billing/subscription";
            case NONE -> "";
        };
    }

    // --- persistence ------------------------------------------------------------------------------
    /* Stored as one escaped field, like the request-field compatibility: wire;path. */

    private static final char FIELD = ';';
    private static final int FIELD_COUNT = 2;

    public String encode() {
        return wire.name() + FIELD + path;
    }

    /** @return the decoded value, or {@code null} when the text is not one this build understands */
    public static LlmBalanceSpec decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] fields = encoded.split(String.valueOf(FIELD), -1);
        if (fields.length != FIELD_COUNT) {
            return null;
        }
        BalanceWire wire = parseWire(fields[0]);
        if (wire == null) {
            return null;
        }
        String path = fields[1] == null ? "" : fields[1].trim();
        if (!isValidPath(path)) {
            return null;
        }
        return new LlmBalanceSpec(wire, path);
    }

    private static BalanceWire parseWire(String raw) {
        String normalized = raw == null ? "" : raw.trim();
        for (BalanceWire candidate : BalanceWire.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * A path the probe can append to an origin.
     *
     * <p>Only a path is accepted: an absolute URL would let the credential be sent to a host the entry
     * does not name, so the endpoint always comes from the base URL the player configured.</p>
     */
    public static boolean isValidPath(String path) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        return path.length() <= MAX_PATH_CHARS
                && path.startsWith("/")
                && !path.contains("://")
                && !path.contains(" ")
                && !path.contains("\n");
    }
}