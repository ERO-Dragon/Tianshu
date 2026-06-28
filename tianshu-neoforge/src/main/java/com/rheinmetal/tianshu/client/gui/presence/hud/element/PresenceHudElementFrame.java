package com.rheinmetal.tianshu.client.gui.presence.hud.element;

import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;

public record PresenceHudElementFrame(
        String elementId,
        PresenceHudElementType type,
        PresenceHudElementState state,
        PresenceHudDisplay display,
        PresenceHudElementTiming timing
) {
    public PresenceHudElementFrame {
        elementId = elementId == null ? "" : elementId.trim();
        type = type == null ? PresenceHudElementType.STATUS_TEXT : type;
        state = state == null ? PresenceHudElementState.HIDDEN : state;
        display = display == null ? PresenceHudDisplay.HIDDEN : display;
        timing = timing == null ? new PresenceHudElementTiming(0L, 0L) : timing;
    }
}
