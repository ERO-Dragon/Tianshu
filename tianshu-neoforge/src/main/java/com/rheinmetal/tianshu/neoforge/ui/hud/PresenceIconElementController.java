package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;

import java.util.Optional;

public final class PresenceIconElementController implements PresenceHudElementController {
    public static final String ELEMENT_ID = "presence.icon";

    private final PresenceHudSettings settings;
    private final PresenceHudVisualTransition visualTransition;
    private PresenceHudElementState state = PresenceHudElementState.HIDDEN;
    private long stateEnteredAtMillis;

    public PresenceIconElementController(PresenceHudSettings settings) {
        this.settings = settings == null ? PresenceHudSettings.ENABLED : settings;
        this.visualTransition = new PresenceHudVisualTransition(240L);
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
            visualTransition.reset();
            return Optional.empty();
        }
        enter(PresenceHudElementState.ACTIVE, nowMillis);
        PresenceHudLayout layout = PresenceHudLayout.resolve(
                settings,
                context.screenWidth(),
                context.screenHeight()
        );
        visualTransition.accept(display.visualState(), display.listening(), nowMillis, layout);
        PresenceHudVisualParameters visualParameters = visualTransition.sample(nowMillis, layout);
        return Optional.of(new PresenceHudElementFrame(
                ELEMENT_ID,
                PresenceHudElementType.ICON,
                state,
                display,
                new PresenceHudElementTiming(nowMillis, stateEnteredAtMillis),
                visualParameters
        ));
    }

    @Override
    public void reset() {
        state = PresenceHudElementState.HIDDEN;
        stateEnteredAtMillis = 0L;
        visualTransition.reset();
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
        return display != null && display.visible() && settings.iconEnabled();
    }
}
