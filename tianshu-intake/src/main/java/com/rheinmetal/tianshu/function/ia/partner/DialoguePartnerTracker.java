package com.rheinmetal.tianshu.function.ia.partner;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;

import java.util.Optional;

/**
 * The lease one participant holds after winning a turn.
 *
 * <p>A lease is the "no hard claim this time, so the same participant keeps it" window. It belongs to the
 * winner of a turn, not to the pre-winner, and is renewed every time that participant wins again. A
 * participant whose tier is {@code 0s} never gets one, and a new hard claim by someone else clears it.</p>
 */
public final class DialoguePartnerTracker {
    private DialogueParticipantDescriptor holder;
    private long leaseUntilMillis;
    private long leaseDurationMillis;

    /** Grants (or renews) the lease of the participant that just won; a zero tier clears it instead. */
    public synchronized void grant(DialogueParticipantDescriptor owner, long nowMillis) {
        if (owner == null || !owner.holdTier().holdsTurn()) {
            clear();
            return;
        }
        holder = owner;
        leaseDurationMillis = owner.holdTier().durationMillis();
        leaseUntilMillis = nowMillis + leaseDurationMillis;
    }

    public synchronized void clear() {
        holder = null;
        leaseUntilMillis = 0L;
        leaseDurationMillis = 0L;
    }

    public synchronized boolean leaseAlive(long nowMillis) {
        prune(nowMillis);
        return holder != null;
    }

    public synchronized Optional<DialogueParticipantDescriptor> holder(long nowMillis) {
        prune(nowMillis);
        return Optional.ofNullable(holder);
    }

    public synchronized String holderParticipantId(long nowMillis) {
        prune(nowMillis);
        return holder == null ? "" : holder.participantId();
    }

    public synchronized long leaseUntilMillis(long nowMillis) {
        prune(nowMillis);
        return holder == null ? 0L : leaseUntilMillis;
    }

    public synchronized long leaseDurationMillis(long nowMillis) {
        prune(nowMillis);
        return holder == null ? 0L : leaseDurationMillis;
    }

    private void prune(long nowMillis) {
        if (holder != null && nowMillis >= leaseUntilMillis) {
            clear();
        }
    }
}