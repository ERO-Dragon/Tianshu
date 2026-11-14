package com.rheinmetal.tianshu.function.presence.partner;

import com.rheinmetal.tianshu.function.presence.payload.PresencePartnerPayload;

/**
 * What the partner marker should draw right now, resolved from the last {@code PRESENCE.PARTNER} push.
 *
 * <p>The lease is a local countdown: the deadline and the tier duration come from the payload once, and every
 * frame derives its progress from the monotonic clock. Nothing is re-requested and nothing changes over the
 * wire as time passes, which is why a dropped push cannot freeze the ring mid-way.</p>
 */
public record PresencePartnerSnapshot(
        boolean present,
        String moduleId,
        String participantId,
        String icon,
        boolean defaultOwner,
        long holdUntilMillis,
        long holdDurationMillis,
        long updatedAtMillis
) {
    public static final PresencePartnerSnapshot HIDDEN =
            new PresencePartnerSnapshot(false, "", "", "", false, 0L, 0L, 0L);

    public PresencePartnerSnapshot {
        moduleId = clean(moduleId);
        participantId = clean(participantId);
        icon = clean(icon);
        holdUntilMillis = Math.max(0L, holdUntilMillis);
        holdDurationMillis = Math.max(0L, holdDurationMillis);
    }

    /** Reads a push; a payload without a participant means there is nobody to show. */
    public static PresencePartnerSnapshot from(PresencePartnerPayload payload) {
        if (payload == null || !payload.present()) {
            return HIDDEN;
        }
        return new PresencePartnerSnapshot(
                true,
                payload.moduleId(),
                payload.participantId(),
                payload.icon(),
                payload.defaultOwner(),
                payload.holdUntilMillis(),
                payload.holdDurationMillis(),
                payload.updatedAtMillis()
        );
    }

    /**
     * Whether the partner icon itself is drawn.
     *
     * <p>Only the icon decides this. A default owner that registered an icon is a partner like any other and is
     * drawn like one — the player picked it, so it should be visible. The host's own assistant registers no icon,
     * which is exactly why the main icon's shader speaks for it; a participant without a usable icon is only
     * diagnosable, never a broken picture.</p>
     */
    public boolean showIcon() {
        return present && !icon.isBlank();
    }

    public boolean hasLease() {
        return holdUntilMillis > 0L && holdDurationMillis > 0L;
    }

    /** Whether the lease is still running at {@code nowMillis}; a marker without a lease always lives. */
    public boolean aliveAt(long nowMillis) {
        return present && (!hasLease() || nowMillis < holdUntilMillis);
    }

    /**
     * The ring's progress: how much of the lease is left, from {@code 1} down to {@code 0}.
     *
     * <p>Only meaningful when {@link #showRingAt(long)} is true; without a running lease there is nothing to
     * count down and the ring is not drawn at all.</p>
     */
    public float remainingFraction(long nowMillis) {
        if (!hasLease()) {
            return 0.0F;
        }
        long remaining = Math.max(0L, holdUntilMillis - nowMillis);
        return Math.min(1.0F, remaining / (float) holdDurationMillis);
    }

    /** The ring is drawn only for the participant whose lease is actually running. */
    public boolean showRingAt(long nowMillis) {
        return showIcon() && hasLease() && aliveAt(nowMillis);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}