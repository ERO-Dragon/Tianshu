package com.rheinmetal.tianshu.api.client.host.hud;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a feature declares the shader assets behind its HUD programs.
 *
 * <p>The platform creates {@code ShaderInstance}s; the feature owns the files, their uniforms and their look. A
 * feature declares the asset id while its jar loads (before the client's shader event), and the platform registers
 * whatever has been declared. An absent feature declares nothing, its family simply has no program, and that layer
 * draws nothing — the platform never needs a list of features or their resource paths.</p>
 *
 * <p>Declarations are keyed by {@link HudProgram}, so a family has exactly one program; declaring the same family
 * again (a reload of the same mod, a test) replaces it.</p>
 */
public final class HudShaderCatalog {

    private static final Map<HudProgram, String> DECLARED = new ConcurrentHashMap<>();

    private HudShaderCatalog() {
    }

    /**
     * Declares the shader asset for one program family.
     *
     * @param program  the family the platform's HUD layers ask for
     * @param shaderId a namespaced asset id, e.g. {@code "example_mod:my_hud_loading"}
     */
    public static void declare(HudProgram program, String shaderId) {
        HudProgram effective = Objects.requireNonNull(program, "program");
        if (shaderId == null || shaderId.isBlank()) {
            throw new IllegalArgumentException("shader id must not be blank for " + effective);
        }
        String trimmed = shaderId.trim();
        if (!trimmed.contains(":")) {
            throw new IllegalArgumentException("shader id must be namespaced: " + trimmed);
        }
        DECLARED.put(effective, trimmed);
    }

    /** The declarations the platform's shader event should create, keyed by family; empty when no feature declared. */
    public static Map<HudProgram, String> declarations() {
        return Map.copyOf(DECLARED);
    }

    /** Drops every declaration; headless assembly and tests use this. */
    public static void clear() {
        DECLARED.clear();
    }
}
