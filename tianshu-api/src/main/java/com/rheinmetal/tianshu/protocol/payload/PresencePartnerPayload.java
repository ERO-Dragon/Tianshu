package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

/**
 * Who the player is talking to — or would be talking to if they spoke right now.
 *
 * <p>The arbiter writes this on presence's channel; presence only renders it. A payload without module and
 * participant ids means "nobody", and the marker hides.</p>
 *
 * <p>{@code holdUntilMillis == 0} means there is no lease: the entry is a pre-winner (aimed at, or holding the
 * matching item) or the default owner. A positive value is the end of the lease the participant got when it
 * won a turn, and {@code holdDurationMillis} is the tier that lease started from, so a countdown ring can
 * show how much of it is left.</p>
 */
public record PresencePartnerPayload(
        String playerId,
        String moduleId,
        String participantId,
        String icon,
        boolean defaultOwner,
        long holdUntilMillis,
        long holdDurationMillis,
        long updatedAtMillis
) implements ITianshuPayload {
    public PresencePartnerPayload {
        playerId = sanitize(playerId);
        moduleId = sanitize(moduleId);
        participantId = sanitize(participantId);
        icon = sanitize(icon);
        holdUntilMillis = Math.max(0L, holdUntilMillis);
        holdDurationMillis = Math.max(0L, holdDurationMillis);
        updatedAtMillis = updatedAtMillis > 0L ? updatedAtMillis : System.currentTimeMillis();
    }

    /** Nobody to show; presence hides the partner marker. */
    public static PresencePartnerPayload empty(String playerId, long updatedAtMillis) {
        return new PresencePartnerPayload(playerId, "", "", "", false, 0L, 0L, updatedAtMillis);
    }

    public boolean present() {
        return !moduleId.isBlank() && !participantId.isBlank();
    }

    public boolean hasLease() {
        return holdUntilMillis > 0L;
    }

    /** Whether two payloads describe the same display state, ignoring when they were produced. */
    public boolean sameDisplay(PresencePartnerPayload other) {
        return other != null
                && playerId.equals(other.playerId())
                && moduleId.equals(other.moduleId())
                && participantId.equals(other.participantId())
                && icon.equals(other.icon())
                && defaultOwner == other.defaultOwner()
                && holdUntilMillis == other.holdUntilMillis()
                && holdDurationMillis == other.holdDurationMillis();
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}