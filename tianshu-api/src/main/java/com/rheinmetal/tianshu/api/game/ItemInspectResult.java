package com.rheinmetal.tianshu.api.game;

import java.util.List;

/**
 * What one stack in the inventory actually is.
 *
 * <p>{@code body.inventory} reports {@code (slot, id, count)}, which is enough to know the actor has "an iron pickaxe" and
 * not enough to know whether wearing it out is a bad idea. Durability, the components attached to the stack and the
 * custom name are what make a decision possible — otherwise a caller learns its tool broke by being refused.</p>
 *
 * <p>{@code components} are reported as ids rather than interpreted: a modded stack carries its own data, and naming the
 * component is honest information where guessing at its meaning would not be. Enchantments are the one family worth
 * spelling out, because "is this enchanted" is a decision, not a curiosity.</p>
 */
public record ItemInspectResult(
        int slot,
        String itemId,
        int count,
        int maxStackSize,
        boolean damageable,
        int damage,
        int maxDamage,
        int remainingDurability,
        String customName,
        List<String> enchantments,
        List<String> components,
        String message) {

    public ItemInspectResult {
        itemId = itemId == null ? "" : itemId;
        customName = customName == null ? "" : customName;
        enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
        components = components == null ? List.of() : List.copyOf(components);
        message = message == null ? "" : message;
    }

    /** A refusal: nothing was read, and the message says why. */
    public static ItemInspectResult failure(int slot, String message) {
        return new ItemInspectResult(slot, "", 0, 0, false, 0, 0, 0, "", List.of(), List.of(), message);
    }

    /** True when a stack was read. An empty slot is a refusal, not a stack with no name. */
    public boolean read() {
        return !itemId.isBlank();
    }

    /** True when this stack will break after {@code remainingDurability} more uses. */
    public boolean nearlyWornOut() {
        return damageable && remainingDurability > 0 && remainingDurability <= maxDamage / 10;
    }
}