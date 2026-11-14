package com.rheinmetal.tianshu.api.assembly;

/**
 * Which optional mods are installed right now.
 *
 * <p>Modules are platform-free, so they cannot ask the loader themselves. This port is how a module learns
 * whether the partner it <em>integrates</em> with is present: an optional dependency is only safe when the
 * code touching the partner's classes is loaded conditionally, and this is the condition.</p>
 *
 * <p>A <em>required</em> dependency never asks this question — it is declared as a hard dependency and its
 * classes are always present. Asking here means "if present, integrate; otherwise stay inert".</p>
 *
 * <p>Implementations are provided by the platform adapter and registered before module assembly. When no
 * implementation is registered — the unit-test or headless case — {@link #none()} answers {@code false},
 * which is the safe reading: an unknown partner is treated as absent.</p>
 */
@FunctionalInterface
public interface InstalledMods {

    boolean isLoaded(String modId);

    /** The stand-in used when nothing is registered: every optional partner counts as absent. */
    static InstalledMods none() {
        return modId -> false;
    }
}
