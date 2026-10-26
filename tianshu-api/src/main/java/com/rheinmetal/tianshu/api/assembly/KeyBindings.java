package com.rheinmetal.tianshu.api.assembly;

/**
 * Where a module asks for a key of its own.
 *
 * <p>Without this, a module that wants a key has two bad options: ship platform-specific code (which costs it
 * the universal jar and breaks the platform-neutral rule), or have the kernel grow a constant and a binding
 * for a feature it does not own. Declaring is the third option: the module states an id, a default key code
 * and the translation key of its category, and the platform does the rest.</p>
 *
 * <p>The declared key publishes on {@link InputSignals#keyChannel(String)}, so the module reacts by
 * subscribing — it still never sees a {@code KeyMapping}, and it does not learn whether a human or anything
 * else pressed the key.</p>
 *
 * <p>Conflicts are resolved by ownership, not by overwriting: the first declaration of a key code keeps it,
 * later ones are registered unbound and reported through diagnostics, so a player sees an unbound key in the
 * controls screen instead of silently stealing someone else's.</p>
 */
public interface KeyBindings {

    /**
     * Declares a key. Calling it twice with the same id replaces the earlier declaration, which is what a
     * module reload does.
     *
     * @param keyId          stable identifier, also the mapping's registration name and translation key suffix
     * @param defaultKeyCode a GLFW key code from {@code InputConstants}, used only until the player rebinds
     * @param categoryLangKey the translation key of the controls-screen category this key belongs under
     */
    void declare(String keyId, int defaultKeyCode, String categoryLangKey);
}
