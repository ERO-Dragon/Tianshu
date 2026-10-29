package com.rheinmetal.tianshu.function.presence.partner;

import com.rheinmetal.tianshu.protocol.payload.PresencePartnerPayload;

import java.util.Objects;

/**
 * Holds the newest partner push so the render thread can read it without touching the protocol.
 *
 * <p>Writes arrive on the protocol thread, reads happen every frame; the snapshot is an immutable record, so a
 * frame sees either the old value or the new one, never a half-updated one. A world session boundary clears it:
 * a marker from the previous world must not survive into the next one.</p>
 */
public final class PresencePartnerStore {
    /**
     * How long a marker may outlive its own lease deadline without a newer push.
     *
     * <p>This is not a second lease: it only keeps a lease-holding marker from staying on screen forever if the
     * arbiter stopped publishing and never sent the "nobody" push. A pre-winner has no deadline to outlive and is
     * replaced by the next push or cleared at a world boundary.</p>
     */
    private static final long LEASE_GRACE_MILLIS = 1_000L;

    private volatile PresencePartnerSnapshot snapshot = PresencePartnerSnapshot.HIDDEN;

    /** Applies one push; an empty payload hides the marker and is still kept as-is. */
    public void accept(PresencePartnerPayload payload) {
        snapshot = PresencePartnerSnapshot.from(payload);
    }

    public void clear() {
        snapshot = PresencePartnerSnapshot.HIDDEN;
    }

    /**
     * The snapshot to draw at {@code nowMillis}.
     *
     * <p>Who is on screen is the arbiter's decision, so this store does not hide the icon when the lease runs
     * out. The usual thing that happens at that instant is that the same participant is still the pre-winner, and
     * hiding here would make the marker leave the screen by a clock only the client can see, then fly straight
     * back in when the next push lands. Only the ring obeys the deadline, through
     * {@link PresencePartnerSnapshot#showRingAt(long)}.</p>
     */
    public PresencePartnerSnapshot at(long nowMillis) {
        PresencePartnerSnapshot current = snapshot;
        if (!current.present()) {
            return PresencePartnerSnapshot.HIDDEN;
        }
        if (current.hasLease() && nowMillis > current.holdUntilMillis() + LEASE_GRACE_MILLIS) {
            return PresencePartnerSnapshot.HIDDEN;
        }
        return current;
    }

    /** The raw last push, for diagnostics that want to see a lease already past its deadline. */
    public PresencePartnerSnapshot raw() {
        return snapshot;
    }

    public void acceptSnapshot(PresencePartnerSnapshot value) {
        snapshot = Objects.requireNonNull(value, "value");
    }
}