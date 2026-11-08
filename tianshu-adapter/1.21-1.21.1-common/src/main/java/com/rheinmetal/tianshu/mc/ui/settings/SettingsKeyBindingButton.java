package com.rheinmetal.tianshu.mc.ui.settings;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * One key mapping edited in place.
 *
 * <p>The button shows the mapping's current key. Pressing it turns the label into "waiting for input"; the
 * next key press (or mouse button) is written into the mapping, saved, and shown on the same button. Escape
 * cancels. The settings page keeps its layout throughout — no second screen is opened.</p>
 *
 * <p>The widget owns the mapping because only the platform may touch a {@code KeyMapping}; the settings
 * model only carries the mapping's registration name.</p>
 */
public final class SettingsKeyBindingButton extends Button {
    private final KeyMapping mapping;
    private final TianshuSettingsScreen screen;
    private boolean capturing;

    public SettingsKeyBindingButton(int x, int y, int width, int height, KeyMapping mapping,
                                    TianshuSettingsScreen screen) {
        super(x, y, width, height, label(mapping), button -> {
        }, DEFAULT_NARRATION);
        this.mapping = mapping;
        this.screen = screen;
    }

    @Override
    public void onPress() {
        if (!active) {
            return;
        }
        if (capturing) {
            cancelCapture();
            return;
        }
        capturing = true;
        setMessage(Component.translatable("tianshu.gui.key_capture.waiting"));
        if (screen != null) {
            screen.armKeyCapture(this);
        }
    }

    public boolean isCapturing() {
        return capturing;
    }

    /** Handles one key press while waiting; returns false when this button is not capturing. */
    public boolean captureKey(int keyCode, int scanCode) {
        if (!capturing) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            cancelCapture();
            return true;
        }
        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);
        if (key == InputConstants.UNKNOWN) {
            return true;
        }
        apply(key);
        return true;
    }

    /** Handles one mouse button while waiting, so a binding can be a mouse key like vanilla allows. */
    public boolean captureMouse(int button) {
        if (!capturing) {
            return false;
        }
        apply(InputConstants.Type.MOUSE.getOrCreate(button));
        return true;
    }

    private void apply(InputConstants.Key key) {
        mapping.setKey(key);
        KeyMapping.resetMapping();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options != null) {
            minecraft.options.save();
        }
        capturing = false;
        setMessage(label(mapping));
    }

    private void cancelCapture() {
        capturing = false;
        setMessage(label(mapping));
    }

    private static Component label(KeyMapping mapping) {
        return mapping.isUnbound()
                ? Component.translatable("tianshu.gui.key_capture.unbound")
                : mapping.getTranslatedKeyMessage();
    }
}