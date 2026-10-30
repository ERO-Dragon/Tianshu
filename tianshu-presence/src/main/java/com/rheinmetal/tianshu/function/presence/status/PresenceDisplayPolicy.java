package com.rheinmetal.tianshu.function.presence.status;

import com.rheinmetal.tianshu.function.presence.PresenceTextProvider;
import com.rheinmetal.tianshu.function.presence.model.PresenceActivitySnapshot;
import com.rheinmetal.tianshu.function.presence.model.PresencePrimaryState;

import java.util.Locale;

/**
 * Maps one aggregated snapshot to the words the HUD shows.
 *
 * <p>Only the primary state is worded; listening is one of those states, so its text comes from the same key rule as
 * every other state. The background task is a parallel mark, not a sentence. A state without a translation renders no
 * text, which hides the status line instead of leaking a key.</p>
 */
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
        String key = STATUS_KEY_PREFIX + keySuffix(effective.primaryState());
        String text = textProvider.exists(key) ? textProvider.text(key) : "";
        return new PresenceHudDisplay(
                true,
                text,
                effective.primaryState(),
                effective.backgroundTask(),
                effective.primarySourceId()
        );
    }

    private String keySuffix(PresencePrimaryState state) {
        return (state == null ? PresencePrimaryState.IDLE : state).name().toLowerCase(Locale.ROOT);
    }
}