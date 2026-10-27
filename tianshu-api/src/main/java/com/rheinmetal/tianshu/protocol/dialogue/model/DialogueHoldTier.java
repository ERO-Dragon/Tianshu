package com.rheinmetal.tianshu.protocol.dialogue.model;

/**
 * How long a participant keeps the turn after winning one, declared at registration.
 *
 * <p>The tier is a hold: while it runs, a new turn with no hard claim goes back to the same participant
 * instead of the default owner. {@link #NONE} means the participant never holds — it has to win every turn
 * with a hard claim.</p>
 */
public enum DialogueHoldTier {
    NONE(0L),
    FIVE_SECONDS(5_000L),
    TEN_SECONDS(10_000L),
    TWENTY_SECONDS(20_000L);

    private final long durationMillis;

    DialogueHoldTier(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public boolean holdsTurn() {
        return durationMillis > 0L;
    }
}
