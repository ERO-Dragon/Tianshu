package com.rheinmetal.tianshu.mc.ui.settings;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * One key mapping edited in place.
 *
 * <p>The button shows the mapping's current key. Pressing it turns the label into "waiting for input"; the
 * next key press (or mouse button) is written into the mapping, saved, and shown on the same button. The
 * settings page keeps its layout throughout — no second screen is opened.</p>
 *
 * <p>The widget owns the mapping because only the platform may touch a {@code KeyMapping}; the settings
 * model only carries the mapping's registration name. It receives an already resolved {@code InputConstants.Key},
 * so the screen keeps the input shape of its own Minecraft line (loose key codes on the 1.20–1.21.8 lines,
 * one input record from 1.21.9 on). Escape is also recognised by the screen.</p>
 */
final class SettingsKeyBindingButton {
    private final Button button;
    private final KeyMapping mapping;
    private final TianshuSettingsScreen screen;
    private boolean capturing;

    SettingsKeyBindingButton(int x, int y, int width, int height, KeyMapping mapping, TianshuSettingsScreen screen) {
        this.mapping = mapping;
        this.screen = screen;
        this.button = Button.builder(label(mapping), pressed -> onPress())
                .pos(x, y)
                .size(width, height)
                .build();
    }

    Button widget() {
        return button;
    }

    boolean isCapturing() {
        return capturing;
    }

    private void onPress() {
        if (!button.active) {
            return;
        }
        if (capturing) {
            cancelCapture();
            return;
        }
        capturing = true;
        button.setMessage(Component.translatable("tianshu.gui.key_capture.waiting"));
        if (screen != null) {
            screen.armKeyCapture(this);
        }
    }

    /** Handles the next key or mouse button while waiting; returns false when this row is not capturing. */
    boolean capture(InputConstants.Key key) {
        if (!capturing) {
            return false;
        }
        if (key == null || key == InputConstants.UNKNOWN) {
            return true;
        }
        apply(key);
        return true;
    }

    void cancelCapture() {
        capturing = false;
        button.setMessage(label(mapping));
    }

    private void apply(InputConstants.Key key) {
        mapping.setKey(key);
        KeyMapping.resetMapping();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.options != null) {
            minecraft.options.save();
        }
        capturing = false;
        button.setMessage(label(mapping));
    }

    private static Component label(KeyMapping mapping) {
        return mapping.isUnbound()
                ? Component.translatable("tianshu.gui.key_capture.unbound")
                : mapping.getTranslatedKeyMessage();
    }
}