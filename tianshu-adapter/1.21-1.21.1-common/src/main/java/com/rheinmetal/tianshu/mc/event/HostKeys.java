package com.rheinmetal.tianshu.mc.event;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;

/**
 * The two keys the host itself owns.
 *
 * <p>They are registered on every loader and in every build, so a player can rebind them from the controls
 * screen whether or not a module is installed; whether pressing one does anything is decided by whether a module
 * subscribes to the channel it publishes, not by this registration.</p>
 */
public final class HostKeys {

    /** Push to talk: held down, released when the player lets go. */
    public static KeyMapping voice() {
        return new KeyMapping(
                "key.tianshu.activate",
                InputConstants.Type.KEYSYM,
                org.lwjgl.glfw.GLFW.GLFW_KEY_V,
                "key.categories.tianshu"
        );
    }

    /** Opens the typed-dialogue bar, when something is subscribed to the text channel. */
    public static KeyMapping textInput() {
        return new KeyMapping(
                "key.tianshu.text_input",
                InputConstants.Type.KEYSYM,
                org.lwjgl.glfw.GLFW.GLFW_KEY_H,
                "key.categories.tianshu"
        );
    }

    private HostKeys() {
    }
}
