package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;

import java.util.Optional;

public final class PresenceStatusTextElementController implements PresenceHudElementController {
    public static final String ELEMENT_ID = "presence.status_text";

    private final PresenceHudSettings settings;
    private PresenceHudElementState state = PresenceHudElementState.HIDDEN;
    private long stateEnteredAtMillis = 0L;

    public PresenceStatusTextElementController(PresenceHudSettings settings) {
        this.settings = settings == null ? PresenceHudSettings.ENABLED : settings;
    }

    @Override
    public Optional<PresenceHudElementFrame> update(PresenceHudElementUpdateContext context) {
        if (context == null) {
            return Optional.empty();
        }
        long nowMillis = context.nowMillis();
        PresenceHudDisplay display = context.display();
        if (!visible(display)) {
            enter(PresenceHudElementState.HIDDEN, nowMillis);
            return Optional.empty();
        }
        enter(PresenceHudElementState.ACTIVE, nowMillis);
        return Optional.of(new PresenceHudElementFrame(
                ELEMENT_ID,
                PresenceHudElementType.STATUS_TEXT,
                state,
                display,
                new PresenceHudElementTiming(nowMillis, stateEnteredAtMillis)
        ));
    }

    @Override
    public void reset() {
        state = PresenceHudElementState.HIDDEN;
        stateEnteredAtMillis = 0L;
    }

    private void enter(PresenceHudElementState nextState, long nowMillis) {
        if (state != nextState) {
            state = nextState;
            stateEnteredAtMillis = nowMillis;
        } else if (stateEnteredAtMillis <= 0L) {
            stateEnteredAtMillis = nowMillis;
        }
    }

    private boolean visible(PresenceHudDisplay display) {
        return display != null
                && display.visible()
                && !display.text().isBlank()
                && settings.statusTextEnabled();
    }
}
