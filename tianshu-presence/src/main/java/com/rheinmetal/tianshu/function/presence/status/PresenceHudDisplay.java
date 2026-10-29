package com.rheinmetal.tianshu.function.presence.status;

import com.rheinmetal.tianshu.function.presence.model.PresencePrimaryState;
import com.rheinmetal.tianshu.function.presence.hud.PresenceHudVisualState;

/**
 * The immutable snapshot the HUD draws: one primary state, the words for it, and the two parallel marks.
 *
 * <p>{@code backgroundTask} and {@code listening} are what the renderer shows beside the icon; the primary state is
 * what the icon itself becomes. Keeping the three apart is why the HUD never has to decide which of two concurrent
 * module states "wins": only primary states compete.</p>
 */
public record PresenceHudDisplay(
        boolean visible,
        String text,
        PresencePrimaryState primaryState,
        boolean listening,
        boolean backgroundTask,
        String sourceModuleId
) {
    public static final PresenceHudDisplay HIDDEN =
            new PresenceHudDisplay(false, "", PresencePrimaryState.IDLE, false, false, "");

    public PresenceHudDisplay {
        text = text == null ? "" : text.trim();
        primaryState = primaryState == null ? PresencePrimaryState.IDLE : primaryState;
        sourceModuleId = sourceModuleId == null ? "" : sourceModuleId.trim();
    }

    public PresenceHudVisualState visualState() {
        return PresenceHudVisualState.from(primaryState);
    }
}