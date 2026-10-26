package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;

import java.util.Optional;

/**
 * The status-text element: visible only when the current product state has words to show.
 *
 * <p>An idle HUD therefore keeps its icon and drops the line, which is why the element exists separately from
 * the icon instead of being a field of it.</p>
 */
public final class PresenceStatusTextElementController implements PresenceHudElementController {
    public static final String ELEMENT_ID = "presence.status_text";

    private final PresenceHudSettings settings;
    private final PresenceHudElementStateTracker elementState = new PresenceHudElementStateTracker();

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
            elementState.enter(PresenceHudElementState.HIDDEN, nowMillis);
            return Optional.empty();
        }
        elementState.enter(PresenceHudElementState.ACTIVE, nowMillis);
        return Optional.of(new PresenceHudElementFrame(
                ELEMENT_ID,
                PresenceHudElementType.STATUS_TEXT,
                elementState.state(),
                display,
                new PresenceHudElementTiming(nowMillis, elementState.enteredAtMillis())
        ));
    }

    @Override
    public void reset() {
        elementState.reset();
    }

    private boolean visible(PresenceHudDisplay display) {
        return display != null
                && display.visible()
                && !display.text().isBlank()
                && settings.statusTextEnabled();
    }
}
