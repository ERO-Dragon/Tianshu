package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.partner.PresencePartnerSnapshot;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;

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