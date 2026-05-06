package com.rheinmetal.tianshu.function.ir.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class Intent {

    private static final Map<String, Intent> REGISTRY = new LinkedHashMap<>();

    public static final Intent UNKNOWN = register("UNKNOWN");

    private final String name;

    private Intent(String name) {
        this.name = name;
    }

    public static synchronized Intent register(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        String key = name.trim().toUpperCase();
        Intent existing = REGISTRY.get(key);
        if (existing != null) {
            return existing;
        }
        Intent intent = new Intent(key);
        REGISTRY.put(key, intent);
        return intent;
    }

    public static Intent valueOf(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        String key = name.trim().toUpperCase();
        Intent intent = REGISTRY.get(key);
        return intent != null ? intent : UNKNOWN;
    }

    public static Set<String> registeredNames() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Intent other)) return false;
        return this.name.equals(other.name);
    }
}
