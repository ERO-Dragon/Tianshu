package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * A namespaced identity for one HUD shader program.
 *
 * <p>The feature that owns the shader also owns the id; the platform only uses it as the lookup key for the compiled
 * program and for the feature's declaration in {@link HudShaderCatalog}. The value is validated as a simple
 * {@code namespace:path} string, the same shape a resource id uses, so a module can add a program without reserving
 * a value in the api.</p>
 */
public record HudProgramId(String value) {

    public HudProgramId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("program id must not be blank");
        }
        value = value.trim();
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException("program id must be namespaced: " + value);
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("program id must not contain whitespace: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
