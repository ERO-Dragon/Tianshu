package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;

/**
 * Immutable input shared by all HUD element controllers for one rendered frame.
 * {@code nowMillis} is elapsed monotonic render time, not a gameplay tick counter.
 */
public record PresenceHudElementUpdateContext(
        PresenceHudDisplay display,
        long nowMillis,
        int screenWidth,
        int screenHeight
) {
    public PresenceHudElementUpdateContext {
        display = display == null ? PresenceHudDisplay.HIDDEN : display;
        nowMillis = Math.max(0L, nowMillis);
        screenWidth = Math.max(1, screenWidth);
        screenHeight = Math.max(1, screenHeight);
    }
}
