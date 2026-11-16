package com.rheinmetal.tianshu.api.entity;

import java.util.Locale;
import java.util.Objects;

/**
 * A module's declaration of one entity type it wants the adapter to register.
 *
 * <p>The declaration is platform-free on purpose: the adapter owns the loader call, and the neutral part is only what
 * the game needs to build a type — identifier, size, and whether fire hurts it. Behaviour never comes from the class;
 * it comes from the body's capability set and ports, so every declared type can share one entity class.</p>
 *
 * <p>Declarations must be made <b>before the loader's registration event</b>: Minecraft accepts entity types only
 * during that pass, so a declaration made later is refused by {@link EntityTypes} instead of being silently dropped at
 * spawn time.</p>
 *
 * @param modId      the registering mod, used as the namespace
 * @param name       the path inside that namespace
 * @param width      collision width in blocks, positive
 * @param height     collision height in blocks, positive
 * @param fireImmune whether fire and lava leave it unharmed
 */
public record EntityTypeSpec(String modId, String name, float width, float height, boolean fireImmune) {

    public EntityTypeSpec {
        modId = requireIdentifier(modId, "modId");
        name = requireIdentifier(name, "name");
        if (!Float.isFinite(width) || width <= 0.0F) {
            throw new IllegalArgumentException("width must be positive: " + width);
        }
        if (!Float.isFinite(height) || height <= 0.0F) {
            throw new IllegalArgumentException("height must be positive: " + height);
        }
    }

    /** The {@code modId:name} identifier a spawn call and a registry entry both use. */
    public String id() {
        return modId + ":" + name;
    }

    private static String requireIdentifier(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_' || character == '-' || character == '.';
            if (!allowed) {
                throw new IllegalArgumentException(field + " may only contain [a-z0-9_.-]: " + value);
            }
        }
        return trimmed;
    }
}