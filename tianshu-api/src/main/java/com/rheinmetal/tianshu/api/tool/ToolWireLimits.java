package com.rheinmetal.tianshu.api.tool;

/**
 * The one place that decides what fits on the tool wire.
 *
 * <p>Requests are validated strictly; a result is clamped, never dropped: a dropped answer leaves the caller
 * waiting for a timeout after the action already ran, while an oversized result can be replaced by a structured
 * failure the model can reason about. The packet classes call this, so the limits exist once instead of once per
 * generation.</p>
 */
public final class ToolWireLimits {

    public static final int MAX_REQUEST_ID = 64;
    public static final int MAX_TOOL_ID = 64;
    /** One less than the 32767-byte {@code writeUtf} limit, so a request or a result can always be encoded. */
    public static final int MAX_JSON = 32_767;
    public static final int MAX_CODE = 64;
    public static final int MAX_MESSAGE = 512;
    public static final int MAX_TOOLS = 64;

    private ToolWireLimits() {
    }

    /** The answer actually put on the wire. */
    public record Fitted(boolean ok, String valueJson, String errorCode, String message) {
    }

    public static Fitted fitResult(boolean ok, String valueJson, String errorCode, String message) {
        String value = valueJson == null ? "" : valueJson;
        if (value.length() > MAX_JSON) {
            return new Fitted(false, "", "FAILED", "result exceeds the wire limit");
        }
        return new Fitted(ok, value, clamp(errorCode, MAX_CODE), clamp(message, MAX_MESSAGE));
    }

    /** Request fields are rejected when malformed; only result text is clamped. */
    public static String text(String value, String what, int max) {
        if (value == null || value.length() > max) {
            throw new IllegalArgumentException(what + " is missing or longer than " + max);
        }
        return value;
    }

    public static String clamp(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
