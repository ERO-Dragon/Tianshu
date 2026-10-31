package com.rheinmetal.tianshu.mc.ui;

import com.rheinmetal.tianshu.api.assembly.InputSignals;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.mc.ui.settings.UiTexts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

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

    /**
     * 1.21.9 hands over one input record and names the two keys this screen cares about: {@code isConfirmation()} is
     * Enter or keypad Enter, {@code isEscape()} is Esc — the same pair the GLFW constants used to encode here.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation()) {
            submit();
            return true;
        }
        if (event.isEscape()) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
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
