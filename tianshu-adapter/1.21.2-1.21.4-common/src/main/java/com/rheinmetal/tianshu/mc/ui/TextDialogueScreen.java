package com.rheinmetal.tianshu.mc.ui;

import com.rheinmetal.tianshu.api.assembly.InputSignals;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.mc.ui.settings.UiTexts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The typed-dialogue bar: one line, at the chat bar's place, for talking to the assistant.
 *
 * <p>It reuses the vanilla {@link EditBox} — the same widget the chat bar is built from — so input-method
 * composition, selection, clipboard and paste all behave natively. What it does not reuse is the chat
 * screen: there is no command surface and no chat history here, because everything typed is simply a message
 * to the assistant. A slash is an ordinary character.</p>
 *
 * <p>Submitting publishes {@link InputSignals#TEXT_SUBMITTED}; whoever subscribed receives the text.
 * Nothing is sent to the server, so a single-player line never leaks into a multiplayer chat.</p>
 */
public final class TextDialogueScreen extends Screen {

    private static final int FIELD_HEIGHT = 12;
    private static final int MAX_LENGTH = 512;
    private static final int SIDE_MARGIN = 20;

    private EditBox field;
    private final UiText hint;

    /**
     * @param title the bar's title, supplied by the module that owns the assistant's name (or the host fallback)
     * @param hint  the empty-field hint, same ownership
     */
    public TextDialogueScreen(UiText title, UiText hint) {
        super(UiTexts.toComponent(title));
        this.hint = hint;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.max(120, width - SIDE_MARGIN * 2);
        field = new EditBox(font, SIDE_MARGIN, height - 22, fieldWidth, FIELD_HEIGHT, Component.empty());
        field.setHint(UiTexts.toComponent(hint));
        field.setMaxLength(MAX_LENGTH);
        addRenderableWidget(field);
        setInitialFocus(field);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** An empty line is not a turn: closing without publishing is the whole handling of it. */
    private void submit() {
        String text = field == null ? "" : field.getValue().trim();
        if (!text.isEmpty()) {
            InputSignals.publish(InputSignals.TEXT_SUBMITTED, text);
        }
        onClose();
    }
}
