package com.rheinmetal.tianshu.client.host.game;

/**
 * Worn and held items plus the armour numbers the game reports.
 *
 * <p>An empty slot stays {@code null} instead of becoming a zero-count stack: "no chestplate" and "a chestplate
 * with nothing in it" are different things to say to the player.</p>
 */
public record GameEquipment(
        GameInventoryEntry helmet,
        GameInventoryEntry chestplate,
        GameInventoryEntry leggings,
        GameInventoryEntry boots,
        GameInventoryEntry mainHand,
        GameInventoryEntry offHand,
        int armorPoints,
        float armorToughness
) {
    public static final GameEquipment EMPTY = new GameEquipment(null, null, null, null, null, null, 0, 0.0F);

    public GameEquipment {
        armorPoints = Math.max(0, armorPoints);
        armorToughness = Math.max(0.0F, armorToughness);
    }
}