package com.rheinmetal.tianshu.api.game;

/**
 * What one entity is and how it is doing.
 *
 * <p>The read that makes {@code entity.use} and {@code body.attack} answerable: feeding, taming and trading need to know
 * whether the thing is already tame, whose it is, and whether it is a baby; attacking needs to know what health is left.
 * Acting on an entity without these is acting blind.</p>
 *
 * <p>Deliberately not a dump of every field: position, type, name, health, tameness and the held item are what decisions
 * turn on. Trade offers in particular are not here — they belong to a trading read, because a villager's offer list is
 * not "state of the entity", it is a separate structure.</p>
 */
public record EntityInspectResult(
        String entityId,
        String typeId,
        String name,
        String dimension,
        double x,
        double y,
        double z,
        float health,
        float maxHealth,
        boolean alive,
        boolean tamed,
        String ownerId,
        boolean baby,
        String heldItemId,
        String message) {

    public EntityInspectResult {
        entityId = entityId == null ? "" : entityId;
        typeId = typeId == null ? "" : typeId;
        name = name == null ? "" : name;
        dimension = dimension == null ? "" : dimension;
        ownerId = ownerId == null ? "" : ownerId;
        heldItemId = heldItemId == null ? "" : heldItemId;
        message = message == null ? "" : message;
    }

    /** A refusal: nothing was read, and the message says why. */
    public static EntityInspectResult failure(String message) {
        return new EntityInspectResult("", "", "", "", 0.0D, 0.0D, 0.0D, 0.0F, 0.0F, false, false, "", false, "", message);
    }

    /** True when the entity itself was read. */
    public boolean read() {
        return !entityId.isBlank();
    }

    /** True when this entity can be hurt at all, which decides whether attacking it is worth a call. */
    public boolean damageable() {
        return alive && maxHealth > 0.0F;
    }
}