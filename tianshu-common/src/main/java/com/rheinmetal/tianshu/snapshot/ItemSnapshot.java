package com.rheinmetal.tianshu.snapshot;

import java.util.*;

public final class ItemSnapshot {

    public final int slotIndex;
    public final String itemId;
    public final String displayName;
    public final int count;
    public final int maxDamage;
    public final int damage;
    public final float durabilityPercent;
    public final List<String> enchantments;
    public final Map<String, String> attributes;

    public final boolean hasCustomName;
    public final boolean hasMending;
    public final boolean hasCurseOfBinding;
    public final float attackDamage;
    public final float attackSpeed;
    public final float armor;
    public final int foodNutrition;
    public final float foodSaturation;
    public final String itemCategory;

    public ItemSnapshot(
            int slotIndex,
            String itemId,
            String displayName,
            int count,
            int maxDamage,
            int damage,
            float durabilityPercent,
            List<String> enchantments,
            Map<String, String> attributes,
            boolean hasCustomName,
            boolean hasMending,
            boolean hasCurseOfBinding,
            float attackDamage,
            float attackSpeed,
            float armor,
            int foodNutrition,
            float foodSaturation,
            String itemCategory
    ) {
        this.slotIndex = slotIndex;
        this.itemId = itemId;
        this.displayName = displayName;
        this.count = count;
        this.maxDamage = maxDamage;
        this.damage = damage;
        this.durabilityPercent = durabilityPercent;
        this.enchantments = enchantments != null ? enchantments : Collections.emptyList();
        this.attributes = attributes != null ? attributes : Collections.emptyMap();
        this.hasCustomName = hasCustomName;
        this.hasMending = hasMending;
        this.hasCurseOfBinding = hasCurseOfBinding;
        this.attackDamage = attackDamage;
        this.attackSpeed = attackSpeed;
        this.armor = armor;
        this.foodNutrition = foodNutrition;
        this.foodSaturation = foodSaturation;
        this.itemCategory = itemCategory;
    }

    public int getSlotIndex() { return slotIndex; }
    public String getItemId() { return itemId; }
    public String getDisplayName() { return displayName; }
    public int getCount() { return count; }
    public int getMaxDamage() { return maxDamage; }
    public int getDamage() { return damage; }
    public float getDurabilityPercent() { return durabilityPercent; }
    public List<String> getEnchantments() { return enchantments; }
    public Map<String, String> getAttributes() { return attributes; }
    public boolean isHasCustomName() { return hasCustomName; }
    public boolean isHasMending() { return hasMending; }
    public boolean isHasCurseOfBinding() { return hasCurseOfBinding; }
    public float getAttackDamage() { return attackDamage; }
    public float getAttackSpeed() { return attackSpeed; }
    public float getArmor() { return armor; }
    public int getFoodNutrition() { return foodNutrition; }
    public float getFoodSaturation() { return foodSaturation; }
    public String getItemCategory() { return itemCategory; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ItemSnapshot that = (ItemSnapshot) o;
        return maxDamage == that.maxDamage
                && damage == that.damage
                && Objects.equals(itemId, that.itemId)
                && Objects.equals(enchantments, that.enchantments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, maxDamage, damage, enchantments);
    }
}
