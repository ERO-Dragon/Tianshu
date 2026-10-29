package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.partner.PresencePartnerSnapshot;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;

/**
 * Immutable input shared by all HUD element controllers for one rendered frame.
 * {@code nowMillis} is elapsed monotonic render time, not a gameplay tick counter.
 *
 * <p>{@code partner} is resolved against the wall clock by the caller, because a lease deadline is a wall-clock
 * instant; feeding the render clock into it would make the ring drift by however long the process has been up.</p>
 */
public record PresenceHudElementUpdateContext(
        PresenceHudDisplay display,
        long nowMillis,
        int screenWidth,
        int screenHeight,
        PresencePartnerSnapshot partner
) {
    public PresenceHudElementUpdateContext(
            PresenceHudDisplay display,
            long nowMillis,
            int screenWidth,
            int screenHeight
    ) {
        this(display, nowMillis, screenWidth, screenHeight, PresencePartnerSnapshot.HIDDEN);
    }

    public PresenceHudElementUpdateContext {
        display = display == null ? PresenceHudDisplay.HIDDEN : display;
        nowMillis = Math.max(0L, nowMillis);
        screenWidth = Math.max(1, screenWidth);
        screenHeight = Math.max(1, screenHeight);
        partner = partner == null ? PresencePartnerSnapshot.HIDDEN : partner;
    }
}