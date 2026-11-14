package com.rheinmetal.tianshu.api.assembly;

/**
 * Where a module asks for a key of its own.
 *
 * <p>Without this, a module that wants a key has two bad options: ship platform-specific code (which costs it
 * the universal jar and breaks the platform-neutral rule), or have the kernel grow a constant and a binding
 * for a feature it does not own. Declaring is the third option: the module states an id, a default key
 * <em>name</em> and the translation key of its category, and the platform does the rest.</p>
 *
 * <p>The default is a name, not a number: {@code "key.keyboard.v"} or {@code "key.mouse.left"}. A module that
 * only depends on api therefore never has to know the platform's key-code constants; the platform resolves the
 * name, and an unknown name leaves the key unbound with a diagnostic instead of binding the wrong key.</p>
 *
 * <p>The declared key publishes on {@link InputSignals#keyChannel(String)}, so the module reacts by
 * subscribing — it still never sees a {@code KeyMapping}, and it does not learn whether a human or anything
 * else pressed the key.</p>
 *
 * <p>Conflicts are resolved by ownership, not by overwriting: the first declaration of a key keeps it, later
 * ones are registered unbound and reported through diagnostics, so a player sees an unbound key in the
 * controls screen instead of silently stealing someone else's.</p>
 */
public interface KeyBindings {

    /**
     * Declares a key. Calling it twice with the same id replaces the earlier declaration, which is what a
     * module reload does.
     *
     * @param keyId           stable identifier, also the mapping's registration name and translation key suffix
     * @param defaultKeyName  a key name such as {@code "key.keyboard.v"}, used only until the player rebinds
     * @param categoryLangKey the translation key of the controls-screen category this key belongs under
     */
    void declare(String keyId, String defaultKeyName, String categoryLangKey);
}
