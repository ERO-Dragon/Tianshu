package com.rheinmetal.tianshu.api.client.host.hud;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a feature declares the shader asset behind one of its HUD programs.
 *
 * <p>The platform compiles the declared asset; the feature owns the files, their uniforms and their look. A
 * feature declares the asset id while its jar loads (before the client's shader registration), and the platform registers
 * whatever has been declared. An absent feature declares nothing, its program simply has no shader, and that layer
 * draws nothing — the platform never needs a list of features or their resource paths.</p>
 *
 * <p>Declarations are keyed by {@link HudProgramId}, so a program has exactly one shader asset; declaring the same id
 * again (a reload of the same mod, a test) replaces it. The id stays stable for the feature's renderer even when the
 * asset path changes.</p>
 */
public final class HudShaderCatalog {

    private static final Map<HudProgramId, String> DECLARED = new ConcurrentHashMap<>();

    private HudShaderCatalog() {
    }

    /**
     * Declares the shader asset for one program id.
     *
     * @param program  the feature-owned program id the platform's HUD layers ask for
     * @param shaderId a namespaced asset id, e.g. {@code "example_mod:my_hud_program"}
     */
    public static void declare(HudProgramId program, String shaderId) {
        HudProgramId effective = Objects.requireNonNull(program, "program");
        if (shaderId == null || shaderId.isBlank()) {
            throw new IllegalArgumentException("shader id must not be blank for " + effective);
        }
        String trimmed = shaderId.trim();
        if (!trimmed.contains(":")) {
            throw new IllegalArgumentException("shader id must be namespaced: " + trimmed);
        }
        DECLARED.put(effective, trimmed);
    }

    /** The declarations the platform's shader event should create, keyed by program id; empty when nothing declared. */
    public static Map<HudProgramId, String> declarations() {
        return Map.copyOf(DECLARED);
    }

    /** Drops every declaration; headless assembly and tests use this. */
    public static void clear() {
        DECLARED.clear();
    }
}
