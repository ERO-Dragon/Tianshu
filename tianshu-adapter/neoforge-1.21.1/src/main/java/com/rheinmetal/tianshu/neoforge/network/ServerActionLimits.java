package com.rheinmetal.tianshu.neoforge.network;

/** Bounds for the action request/result payloads; every count is checked before allocation. */
final class ServerActionLimits {

    static final int MAX_ID_LENGTH = 64;
    static final int MAX_KIND_LENGTH = 32;
    static final int MAX_PARAMS = 16;
    static final int MAX_KEY_LENGTH = 32;
    static final int MAX_VALUE_LENGTH = 512;

    private ServerActionLimits() {
    }

    static String text(String value, String field, int maximum) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > maximum) {
            throw new IllegalArgumentException("action " + field + " is too long: " + trimmed.length());
        }
        return trimmed;
    }
}
