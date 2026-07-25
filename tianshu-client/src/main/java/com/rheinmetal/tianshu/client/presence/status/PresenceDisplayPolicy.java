package com.rheinmetal.tianshu.client.presence.status;

import com.rheinmetal.tianshu.client.presence.PresenceTextProvider;
import com.rheinmetal.tianshu.client.presence.model.PresenceActivitySnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresencePrimaryState;

import java.util.Locale;

public final class PresenceDisplayPolicy {
    private static final String STATUS_KEY_PREFIX = "tianshu.presence.status.";
    private final PresenceTextProvider textProvider;

    public PresenceDisplayPolicy() {
        this(PresenceTextProvider.NOOP);
    }

    public PresenceDisplayPolicy(PresenceTextProvider textProvider) {
        this.textProvider = textProvider == null ? PresenceTextProvider.NOOP : textProvider;
    }

    public PresenceHudDisplay hudDisplay(PresenceActivitySnapshot snapshot) {
        PresenceActivitySnapshot effective = snapshot == null
                ? PresenceActivitySnapshot.idle(System.currentTimeMillis())
                : snapshot;
        String key = effective.listening()
                ? STATUS_KEY_PREFIX + "listening"
                : STATUS_KEY_PREFIX + keySuffix(effective.primaryState());
        String text = textProvider.exists(key) ? textProvider.text(key) : "";
        return text.isBlank()
                ? PresenceHudDisplay.HIDDEN
                : new PresenceHudDisplay(
                        true,
                        text,
                        effective.primaryState(),
                        effective.listening(),
                        effective.primarySourceId()
                );
    }

    private String keySuffix(PresencePrimaryState state) {
        return (state == null ? PresencePrimaryState.IDLE : state).name().toLowerCase(Locale.ROOT);
    }
}
