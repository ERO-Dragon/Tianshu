package com.rheinmetal.tianshu.snapshot;

import java.util.*;

public final class ItemSnapshot {

    public final int slotIndex;
    public final String itemId;

    /**
     * 期望填入经过 Minecraft 本地化处理后的显示名称（如通过 getHoverName().getString() 获取）。
     * 严禁填入未经翻译的注册表 ID（如 "item.minecraft.diamond_sword"），必须是对应语言的文本（如 "钻石剑"），
     * 以防止 2B 小模型因上下文充斥英文而产生语言混乱。
     */
    public final String displayName;

    public final int count;
    public final int maxDamage;
    public final int damage;
    public final float durabilityPercent;
    public final List<String> enchantments;
    public final Map<String, String> attributes;

    public ItemSnapshot(
            int slotIndex,
            String itemId,
            String displayName,
            int count,
            int maxDamage,
            int damage,
            float durabilityPercent,
            List<String> enchantments,
            Map<String, String> attributes
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
