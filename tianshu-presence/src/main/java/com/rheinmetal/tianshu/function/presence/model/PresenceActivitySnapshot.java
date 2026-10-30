package com.rheinmetal.tianshu.function.presence.model;

/**
 * What the player is experiencing: one primary state, plus the background-work mark.
 *
 * <p>Listening is one of the primary states, not a separate flag: a speech turn is what the player is doing, so it
 * competes for the same single slot as idle, thinking or responding. Only background work stays parallel, because it
 * can run next to any primary state without changing what the player is doing.</p>
 */
public record PresenceActivitySnapshot(
        PresencePrimaryState primaryState,
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
        return new PresenceActivitySnapshot(PresencePrimaryState.IDLE, false, "", nowMillis);
    }
}
