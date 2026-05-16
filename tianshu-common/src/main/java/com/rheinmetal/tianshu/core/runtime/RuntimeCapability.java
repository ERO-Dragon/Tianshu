package com.rheinmetal.tianshu.core.runtime;

import java.util.Objects;

public record RuntimeCapability(String id) {
    public RuntimeCapability {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Capability id must not be blank");
        }
    }

    public static RuntimeCapability of(String id) {
        return new RuntimeCapability(id);
    }
}
