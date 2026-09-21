package com.rheinmetal.tianshu.client.presence.status;

import com.rheinmetal.tianshu.client.presence.model.PresencePrimaryState;
import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualState;

public record PresenceHudDisplay(
        boolean visible,
        String text,
        PresencePrimaryState primaryState,
        boolean listening,
        String sourceModuleId
) {
    public static final PresenceHudDisplay HIDDEN = new PresenceHudDisplay(false, "", PresencePrimaryState.IDLE, false, "");

    public PresenceHudDisplay {
        text = text == null ? "" : text.trim();
        primaryState = primaryState == null ? PresencePrimaryState.IDLE : primaryState;
        sourceModuleId = sourceModuleId == null ? "" : sourceModuleId.trim();
    }

    public PresenceHudVisualState visualState() {
        return PresenceHudVisualState.from(primaryState);
    }
}
