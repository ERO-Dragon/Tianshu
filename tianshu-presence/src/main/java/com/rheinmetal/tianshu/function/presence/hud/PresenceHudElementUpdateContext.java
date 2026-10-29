package com.rheinmetal.tianshu.function.presence.hud;

import com.rheinmetal.tianshu.function.presence.partner.PresencePartnerSnapshot;
import com.rheinmetal.tianshu.function.presence.status.PresenceHudDisplay;

/**
 * Immutable input shared by all HUD element controllers for one rendered frame.
 *
 * <p>Two clocks arrive together because they answer different questions: {@code nowMillis} is elapsed monotonic
 * render time (animation), {@code wallMillis} is the wall clock a lease deadline is compared against. The caller
 * resolves the partner against the wall clock, because feeding the render clock into a deadline would make the ring
 * drift by however long the process has been up.</p>
 */
public record PresenceHudElementUpdateContext(
        PresenceHudDisplay display,
        long nowMillis,
        long wallMillis,
        int screenWidth,
        int screenHeight,
        PresencePartnerSnapshot partner
) {
    public PresenceHudElementUpdateContext(
            PresenceHudDisplay display,
            long nowMillis,
            int screenWidth,
            int screenHeight,
            PresencePartnerSnapshot partner
    ) {
        this(display, nowMillis, System.currentTimeMillis(), screenWidth, screenHeight, partner);
    }

    public PresenceHudElementUpdateContext {
        display = display == null ? PresenceHudDisplay.HIDDEN : display;
        nowMillis = Math.max(0L, nowMillis);
        wallMillis = wallMillis <= 0L ? System.currentTimeMillis() : wallMillis;
        screenWidth = Math.max(1, screenWidth);
        screenHeight = Math.max(1, screenHeight);
        partner = partner == null ? PresencePartnerSnapshot.HIDDEN : partner;
    }
}
