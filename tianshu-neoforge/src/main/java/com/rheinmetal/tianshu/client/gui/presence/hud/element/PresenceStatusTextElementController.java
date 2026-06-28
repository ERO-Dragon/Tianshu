package com.rheinmetal.tianshu.client.gui.presence.hud.element;

import com.rheinmetal.tianshu.client.gui.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class PresenceStatusTextElementController implements PresenceHudElementController {
    public static final String ELEMENT_ID = "presence.status_text";

    private final Supplier<PresenceHudDisplay> displaySupplier;
    private final PresenceHudSettings settings;
    private PresenceHudElementState state = PresenceHudElementState.HIDDEN;

    public PresenceStatusTextElementController(Supplier<PresenceHudDisplay> displaySupplier, PresenceHudSettings settings) {
        this.displaySupplier = Objects.requireNonNull(displaySupplier, "displaySupplier");
        this.settings = settings == null ? PresenceHudSettings.ENABLED : settings;
    }

    @Override
    public Optional<PresenceHudElementFrame> update(long nowMillis) {
        PresenceHudDisplay display = displaySupplier.get();
        if (!visible(display)) {
            state = PresenceHudElementState.HIDDEN;
            return Optional.empty();
        }
        state = PresenceHudElementState.ACTIVE;
        return Optional.of(new PresenceHudElementFrame(ELEMENT_ID, PresenceHudElementType.STATUS_TEXT, state, display));
    }

    private boolean visible(PresenceHudDisplay display) {
        return display != null
                && display.visible()
                && settings.statusTextEnabled()
                && settings.sourceVisible(display.sourceModuleId());
    }
}
