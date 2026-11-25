package com.rheinmetal.tianshu.api.game;

/**
 * What an actor is and how it is doing, in one read.
 *
 * <p>One read rather than five because every one of these answers is needed together: a model deciding whether to keep
 * mining needs the health and the hunger at the same time, and asking three times means deciding on three different
 * moments.</p>
 *
 * <p>Only the actor's own state is here. What it carries is {@code body.inventory}'s job, and what surrounds it is
 * {@code body.scan}'s.</p>
 */
public record ActorStatus(
        String id,
        String kind,
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float health,
        float maxHealth,
        int foodLevel,
        float saturation,
        int air,
        boolean onFire,
        boolean inWater,
        boolean alive,
        String gameMode,
        String message) {

    public ActorStatus {
        id = id == null ? "" : id;
        kind = kind == null ? "" : kind;
        dimension = dimension == null ? "" : dimension;
        gameMode = gameMode == null ? "" : gameMode;
        message = message == null ? "" : message;
    }

    /** A refusal: nothing was read, and the message says why. */
    public static ActorStatus failure(String message) {
        return new ActorStatus("", "", "", 0.0D, 0.0D, 0.0D, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0.0F, 0, false, false, false, "",
                message);
    }

    /** True when the actor itself was read. */
    public boolean read() {
        return !id.isBlank();
    }

    /** Health as a fraction, which is what a "should I retreat" decision actually uses. */
    public float healthFraction() {
        if (maxHealth <= 0.0F) {
            return 0.0F;
        }
        float fraction = health / maxHealth;
        return fraction < 0.0F ? 0.0F : Math.min(fraction, 1.0F);
    }
}