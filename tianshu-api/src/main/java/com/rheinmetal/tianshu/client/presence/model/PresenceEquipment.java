package com.rheinmetal.tianshu.client.presence.model;

/**
 * What the player is wearing and holding, plus the aggregated armour numbers.
 *
 * <p>A slot that holds nothing is {@code null}, not an item with a zero count: {@link PresenceInventoryItem}
 * describes a stack, and a zero-count stack is a different statement from "there is no chestplate". Consumers
 * that decide whether to mention a slot therefore test for {@code null} rather than for emptiness.</p>
 *
 * <p>The aggregate values are derived numbers the game computes for us; they are kept apart from the slots so a
 * consumer can talk about "armour 7" without walking the slots itself.</p>
 *
 * @param helmet        head slot, {@code null} when empty
 * @param chestplate    chest slot, {@code null} when empty
 * @param leggings      legs slot, {@code null} when empty
 * @param boots         feet slot, {@code null} when empty
 * @param mainHand      item in the main hand, {@code null} when empty
 * @param offHand       item in the off hand, {@code null} when empty
 * @param armorPoints   total armour points the game reports for this player
 * @param armorToughness total armour toughness, {@code 0} when the attribute is unavailable
 */
public record PresenceEquipment(
        PresenceInventoryItem helmet,
        PresenceInventoryItem chestplate,
        PresenceInventoryItem leggings,
        PresenceInventoryItem boots,
        PresenceInventoryItem mainHand,
        PresenceInventoryItem offHand,
        int armorPoints,
        float armorToughness
) {
    public PresenceEquipment {
        armorPoints = Math.max(0, armorPoints);
        armorToughness = Math.max(0.0F, armorToughness);
    }

    /** Nothing worn, nothing held, no armour: the shape a snapshot uses when equipment was not captured. */
    public static PresenceEquipment empty() {
        return new PresenceEquipment(null, null, null, null, null, null, 0, 0.0F);
    }
}
