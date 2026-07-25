package com.rheinmetal.tianshu.client.presence.model;

public record PresenceActivitySnapshot(
        PresencePrimaryState primaryState,
        boolean listening,
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
