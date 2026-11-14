package com.rheinmetal.tianshu.api.assembly;

/**
 * The platform's key-binding screen, offered to modules.
 *
 * <p>A module that owns a key cannot write the binding itself: the binding is a Minecraft object that lives in
 * the platform layer, and a module that touched it would stop being platform-free. This port is the same
 * shape as the other platform services a module may request (the file picker, the text provider): the module
 * asks, the platform opens its own screen.</p>
 *
 * <p>The identifier is the mapping's registration name (for example {@code key.tianshu.text_input}), which is
 * also the translation key its label uses — so the row a module shows and the row the controls screen shows
 * are the same key, and there is only ever one binding behind them.</p>
 */
public interface ClientKeyBindings {

    /** Opens the platform's binding screen focused on nothing in particular; an unknown id changes nothing. */
    void openRebindScreen(String keyMappingName);
}
