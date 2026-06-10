package com.rheinmetal.tianshu.function.ir.core;

public final class IRObjectId {
    private static final String ITEM_PREFIX = "item|";
    private static final String ENTITY_PREFIX = "entity|";

    private IRObjectId() {
    }

    public static String item(String rawId) {
        return ITEM_PREFIX + sanitize(rawId);
    }

    public static String entity(String rawId) {
        return ENTITY_PREFIX + sanitize(rawId);
    }

    public static boolean isItem(String value) {
        return value == null || !value.startsWith(ENTITY_PREFIX);
    }

    public static boolean isEntity(String value) {
        return value != null && value.startsWith(ENTITY_PREFIX);
    }

    public static String raw(String value) {
        if (value == null) {
            return "";
        }
        if (value.startsWith(ITEM_PREFIX)) {
            return value.substring(ITEM_PREFIX.length());
        }
        if (value.startsWith(ENTITY_PREFIX)) {
            return value.substring(ENTITY_PREFIX.length());
        }
        return value;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
