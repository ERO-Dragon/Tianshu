package com.rheinmetal.tianshu.mc.event;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;

/**
 * The two keys the host itself owns.
 *
 * <p>They are registered on every loader and in every build, so a player can rebind them from the controls
 * screen whether or not a module is installed; whether pressing one does anything is decided by whether a module
 * subscribes to the channel it publishes, not by this registration.</p>
 *
 * <p>Since 1.21.9 a mapping takes a {@link net.minecraft.client.KeyMapping.Category} rather than the category's
 * translation key, so both keys ask {@link KeyCategories} for the shared Tianshu category — one instance, one
 * registration, one row in the controls screen.</p>
 */
public final class HostKeys {

    /** The category both host keys sit under; its label is the derived {@code key.category.tianshu.keys}. */
    private static final String CATEGORY = "key.categories.tianshu";

    /** Push to talk: held down, released when the player lets go. */
    public static KeyMapping voice() {
        return new KeyMapping(
                "key.tianshu.activate",
                InputConstants.Type.KEYSYM,
                org.lwjgl.glfw.GLFW.GLFW_KEY_V,
                KeyCategories.of(CATEGORY)
        );
    }

    /** Opens the typed-dialogue bar, when something is subscribed to the text channel. */
    public static KeyMapping textInput() {
        return new KeyMapping(
                "key.tianshu.text_input",
                InputConstants.Type.KEYSYM,
                org.lwjgl.glfw.GLFW.GLFW_KEY_H,
                KeyCategories.of(CATEGORY)
        );
    }

    private HostKeys() {
    }
}
