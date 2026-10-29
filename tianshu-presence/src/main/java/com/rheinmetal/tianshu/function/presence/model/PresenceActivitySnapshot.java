package com.rheinmetal.tianshu.function.presence.model;

/**
 * What the player is experiencing: one primary state, plus the two parallel marks that can be true next to it.
 *
 * <p>Listening and background work are separate booleans instead of more primary states, because either can run at
 * the same time as any primary state. They are what the HUD shows beside the icon, not what the icon turns into.</p>
 */
public record PresenceActivitySnapshot(
        PresencePrimaryState primaryState,
        boolean listening,
        boolean backgroundTask,
        String primarySourceId,
        long updatedAtMillis
) {
    public PresenceActivitySnapshot {
        primaryState = primaryState == null ? PresencePrimaryState.IDLE : primaryState;
        primarySourceId = primarySourceId == null ? "" : primarySourceId.trim();
        updatedAtMillis = Math.max(0L, updatedAtMillis);
    }

    public static PresenceActivitySnapshot idle(long nowMillis) {
        return new PresenceActivitySnapshot(PresencePrimaryState.IDLE, false, false, "", nowMillis);
    }
}