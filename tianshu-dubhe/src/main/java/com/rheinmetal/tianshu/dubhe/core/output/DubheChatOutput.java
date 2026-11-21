package com.rheinmetal.tianshu.dubhe.core.output;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.host.ClientPresentationPort;

/**
 * Sends the assistant's finished reply (and a generic failure line) to the player's local chat.
 *
 * <p>Only the completed text is shown: {@link #append} is deliberately ignored so a stream does not become a wall
 * of chat lines. Streaming belongs to the module's HUD layer; this sink is the final, one-line outcome.</p>
 */
public final class DubheChatOutput implements DubheChatOutputSink {

    public static final String FAILURE_KEY = "tianshu.gui.dubhe.output.failed";

    private final ClientPresentationPort presentation;

    public DubheChatOutput(ClientPresentationPort presentation) {
        this.presentation = presentation;
    }

    @Override
    public void complete(DubheOutputContext context, String fullText) {
        if (presentation == null || fullText == null || fullText.isBlank()) {
            return;
        }
        presentation.chat(UiText.literal(fullText));
    }

    @Override
    public void fail(DubheOutputContext context, String reason) {
        if (presentation == null) {
            return;
        }
        presentation.chat(UiText.key(FAILURE_KEY));
    }
}
