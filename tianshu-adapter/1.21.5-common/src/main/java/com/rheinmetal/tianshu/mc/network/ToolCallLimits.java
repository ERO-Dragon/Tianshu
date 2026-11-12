package com.rheinmetal.tianshu.mc.network;

/** Bounds for the tool channel payloads; a malformed packet must be rejected before it becomes state. */
final class ToolCallLimits {

    static final int MAX_REQUEST_ID = 64;
    static final int MAX_TOOL_ID = 64;
    static final int MAX_JSON = 32_768;
    static final int MAX_CODE = 64;
    static final int MAX_MESSAGE = 512;
    static final int MAX_TOOLS = 64;

    private ToolCallLimits() {
    }

    static String text(String value, String what, int max) {
        if (value == null || value.length() > max) {
            throw new IllegalArgumentException(what + " is missing or longer than " + max);
        }
        return value;
    }
}
