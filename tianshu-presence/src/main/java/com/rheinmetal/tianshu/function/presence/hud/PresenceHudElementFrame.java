package com.rheinmetal.tianshu.function.presence.hud;

import com.rheinmetal.tianshu.function.presence.partner.PresencePartnerSnapshot;
import com.rheinmetal.tianshu.function.presence.status.PresenceHudDisplay;

/**
 * One element as it should be drawn this frame: what to show, and every value the platform needs to draw it.
 *
 * <p>It is a value, not a drawing: the platform's renderer receives it and turns it into pixels, so the feature
 * stays free of game types while still deciding the whole appearance.</p>
 */
public record PresenceHudElementFrame(
        String elementId,
        PresenceHudElementType type,
        PresenceHudElementState state,
        PresenceHudDisplay display,
        PresenceHudElementTiming timing,
        PresenceHudVisualParameters visualParameters,
        PresencePartnerSnapshot partner
) {
    public PresenceHudElementFrame(
            String elementId,
            PresenceHudElementType type,
            PresenceHudElementState state,
            PresenceHudDisplay display,
            PresenceHudElementTiming timing,
            PresenceHudVisualParameters visualParameters
    ) {
        this(elementId, type, state, display, timing, visualParameters, PresencePartnerSnapshot.HIDDEN);
    }

    public PresenceHudElementFrame(
            String elementId,
            PresenceHudElementType type,
            PresenceHudElementState state,
            PresenceHudDisplay display,
            PresenceHudElementTiming timing
    ) {
        this(elementId, type, state, display, timing, null, PresencePartnerSnapshot.HIDDEN);
    }

    public PresenceHudElementFrame {
        elementId = elementId == null ? "" : elementId.trim();
        type = type == null ? PresenceHudElementType.STATUS_TEXT : type;
        state = state == null ? PresenceHudElementState.HIDDEN : state;
        display = display == null ? PresenceHudDisplay.HIDDEN : display;
        timing = timing == null ? new PresenceHudElementTiming(0L, 0L) : timing;
        partner = partner == null ? PresencePartnerSnapshot.HIDDEN : partner;
    }
}
