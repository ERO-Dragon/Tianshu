package com.rheinmetal.tianshu.function.presence.status;

import com.rheinmetal.tianshu.function.presence.model.PresencePrimaryState;
import com.rheinmetal.tianshu.function.presence.hud.PresenceHudVisualState;

/**
 * The immutable snapshot the HUD draws: one primary state, the words for it, and the background mark.
 *
 * <p>Listening is one of the primary states, so the icon becomes the listening visual instead of carrying a second
 * flag. {@code backgroundTask} is the only mark that can sit next to a primary state; it never competes for the slot.
 * </p>
 */
public record PresenceHudDisplay(
        boolean visible,
        String text,
        PresencePrimaryState primaryState,
        boolean backgroundTask,
        String sourceModuleId
) {
    public static final PresenceHudDisplay HIDDEN =
            new PresenceHudDisplay(false, "", PresencePrimaryState.IDLE, false, "");

    public PresenceHudDisplay {
        text = text == null ? "" : text.trim();
        primaryState = primaryState == null ? PresencePrimaryState.IDLE : primaryState;
        sourceModuleId = sourceModuleId == null ? "" : sourceModuleId.trim();
    }

    public PresenceHudVisualState visualState() {
        return PresenceHudVisualState.from(primaryState);
    }
}
