package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IInventoryDataProvider;
import com.rheinmetal.tianshu.snapshot.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import org.slf4j.Logger;

import java.util.*;

public class NeoForgeInventoryProvider implements IInventoryDataProvider {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_NBT_NODES = 50;

    private static final Set<String> PROTECTED_ITEM_IDS = Set.of(
            "minecraft:ender_chest",
            "minecraft:shulker_box",
            "minecraft:white_shulker_box",
            "minecraft:orange_shulker_box",
            "minecraft:magenta_shulker_box",
            "minecraft:light_blue_shulker_box",
            "minecraft:yellow_shulker_box",
            "minecraft:lime_shulker_box",
            "minecraft:pink_shulker_box",
            "minecraft:gray_shulker_box",
            "minecraft:light_gray_shulker_box",
            "minecraft:cyan_shulker_box",
            "minecraft:purple_shulker_box",
            "minecraft:blue_shulker_box",
            "minecraft:brown_shulker_box",
            "minecraft:green_shulker_box",
            "minecraft:red_shulker_box",
            "minecraft:black_shulker_box"
    );

    private List<ItemSnapshot> cachedInventoryItems = Collections.emptyList();
    private long cachedInventorySignature = Long.MIN_VALUE;

    @Override
    public ItemSnapshot getMainHandItemData() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        return toItemSnapshot(mc.player.getMainHandItem(), mc.player.getInventory().selected);
    }

    @Override
    public List<ItemSnapshot> getAllInventoryItemsData() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            cachedInventoryItems = Collections.emptyList();
            cachedInventorySignature = Long.MIN_VALUE;
            return cachedInventoryItems;
        }

        Inventory inv = mc.player.getInventory();
        long signature = inventorySignature(inv);
        if (signature == cachedInventorySignature) return cachedInventoryItems;

        List<ItemSnapshot> items = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) items.add(toItemSnapshot(stack, i));
        }
        cachedInventorySignature = signature;
        cachedInventoryItems = Collections.unmodifiableList(items);
        return cachedInventoryItems;
    }

    private long inventorySignature(Inventory inv) {
        long signature = 1125899906842597L;
        int size = inv != null ? inv.getContainerSize() : 0;
        for (int i = 0; i < size; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                signature = signature * 31L + i;
                continue;
            }
            signature = signature * 31L + i;
            signature = signature * 31L + stack.getCount();
            signature = signature * 31L + stack.getDamageValue();
            signature = signature * 31L + stack.getItemHolder().getRegisteredName().hashCode();
            signature = signature * 31L + stack.getComponents().hashCode();
        }
        return signature;
    }

    @Override
    public List<MatchedSlotData> findItemSlotsByName(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || name == null || name.isBlank()) return Collections.emptyList();

        List<MatchedSlotData> results = new ArrayList<>();
        String lowerName = name.toLowerCase();
        Inventory inv = mc.player.getInventory();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            String displayName = LocalizationHelper.safeGetDisplayName(stack.getHoverName().getString());
            String itemId = stack.getItemHolder().getRegisteredName();
            if (displayName.toLowerCase().contains(lowerName) || itemId.toLowerCase().contains(lowerName)) {
                results.add(new MatchedSlotData(i, itemId, displayName, stack.getCount()));
            }
        }

        return results;
    }


    @Override
    public List<ItemSnapshot> findItemsByCategory(String category) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || category == null) return Collections.emptyList();

        List<ItemSnapshot> result = new ArrayList<>();
        Inventory inv = mc.player.getInventory();
        String lowerCat = category.toLowerCase();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            ItemSnapshot snapshot = toItemSnapshot(stack, i);
            if (snapshot != null && lowerCat.equals(snapshot.getItemCategory())) {
                result.add(snapshot);
            }
        }

        return result;
    }

    @Override
    public boolean isProtectedItem(ItemSnapshot item) {
        if (item == null) return false;

        if (item.isHasCustomName()) return true;
        if (item.isHasMending()) return true;
        if (item.isHasCurseOfBinding()) return true;

        if (PROTECTED_ITEM_IDS.contains(item.getItemId())) return true;

        return false;
    }

    private ItemSnapshot toItemSnapshot(ItemStack stack, int slotIndex) {
        if (stack.isEmpty()) return null;

        String itemId = stack.getItemHolder().getRegisteredName();
        String displayName = LocalizationHelper.safeGetDisplayName(stack.getHoverName().getString());
        int count = stack.getCount();
        int maxDamage = stack.getMaxDamage();
        int damage = stack.getDamageValue();

        float durabilityPercent = 1.0f;
        if (maxDamage > 0) {
            durabilityPercent = (float) (maxDamage - damage) / maxDamage;
        }

        List<String> enchantments = extractEnchantments(stack);
        Map<String, String> attributes = extractAttributes(stack);

        boolean hasCustomName = stack.has(DataComponents.CUSTOM_NAME);

        boolean hasMending = false;
        boolean hasCurseOfBinding = false;
        try {
            ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
            if (enchants != null) {
                for (var entry : enchants.entrySet()) {
                    String enchId = entry.getKey().unwrapKey()
                            .map(key -> key.location().toString())
                            .orElse("");
                    if (enchId.contains("mending")) hasMending = true;
                    if (enchId.contains("binding_curse")) hasCurseOfBinding = true;
                }
            }
        } catch (Exception ignored) {}

        float attackDamage = 0f;
        float attackSpeed = 0f;
        float armor = 0f;
        try {
            var attrMods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (attrMods != null) {
                for (var mod : attrMods.modifiers()) {
                    String attrId = mod.attribute().unwrapKey()
                            .map(key -> key.location().toString())
                            .orElse("");
                    if (attrId.contains("attack_damage")) {
                        attackDamage += (float) mod.modifier().amount();
                    } else if (attrId.contains("attack_speed")) {
                        attackSpeed += (float) mod.modifier().amount();
                    } else if (attrId.contains("armor")) {
                        armor += (float) mod.modifier().amount();
                    }
                }
            }
        } catch (Exception ignored) {}

        int foodNutrition = -1;
        float foodSaturation = 0f;
        try {
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null) {
                foodNutrition = food.nutrition();
                foodSaturation = food.saturation();
            }
        } catch (Exception ignored) {}

        String itemCategory = resolveItemCategory(stack);

        return new ItemSnapshot(
                slotIndex, itemId, displayName, count,
                maxDamage, damage, durabilityPercent,
                enchantments, attributes,
                hasCustomName, hasMending, hasCurseOfBinding,
                attackDamage, attackSpeed, armor,
                foodNutrition, foodSaturation, itemCategory
        );
    }

    private String resolveItemCategory(ItemStack stack) {
        try {
            if (stack.getItem() instanceof net.minecraft.world.item.SwordItem
                    || stack.getItem() instanceof net.minecraft.world.item.AxeItem
                    || stack.getItem() instanceof net.minecraft.world.item.TridentItem
                    || stack.getItem() instanceof net.minecraft.world.item.MaceItem) {
                return "weapon";
            }
            if (stack.getItem() instanceof net.minecraft.world.item.PickaxeItem
                    || stack.getItem() instanceof net.minecraft.world.item.ShovelItem
                    || stack.getItem() instanceof net.minecraft.world.item.HoeItem
                    || stack.getItem() instanceof net.minecraft.world.item.ShearsItem) {
                return "tool";
            }
            if (stack.getItem() instanceof net.minecraft.world.item.ArmorItem
                    || stack.getItem() instanceof net.minecraft.world.item.ElytraItem) {
                return "armor";
            }
            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                return "block";
            }
            if (stack.has(DataComponents.FOOD)) {
                return "food";
            }
            if (stack.getItem() instanceof net.minecraft.world.item.BowItem
                    || stack.getItem() instanceof net.minecraft.world.item.CrossbowItem) {
                return "weapon";
            }
        } catch (Exception ignored) {}
        return "misc";
    }

    private List<String> extractEnchantments(ItemStack stack) {
        List<String> enchantmentNames = new ArrayList<>();
        try {
            ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
            if (enchantments != null) {
                enchantments.entrySet().forEach(entry -> {
                    try {
                        Holder<Enchantment> holder = entry.getKey();
                        String enchName = holder.unwrapKey()
                                .map(key -> LocalizationHelper.safeGetDisplayName(
                                        Component.translatable(key.location().toLanguageKey("enchantment")).getString()))
                                .orElse("unknown");
                        int level = entry.getIntValue();
                        enchantmentNames.add(enchName + ":" + level);
                    } catch (Exception e) {
                        LOGGER.warn("解析附魔条目失败: {}", e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.warn("提取附魔信息失败: {}", e.getMessage());
        }
        return enchantmentNames;
    }

    private Map<String, String> extractAttributes(ItemStack stack) {
        Map<String, String> attrs = new LinkedHashMap<>();
        try {
            DataComponentMap components = stack.getComponents();
            if (components == null) return attrs;

            extractCustomData(stack, attrs);
            extractLore(stack, attrs);
            extractDisplayProperties(stack, attrs);

            if (stack.getMaxDamage() > 0) {
                attrs.put("maxDamage", String.valueOf(stack.getMaxDamage()));
                attrs.put("damage", String.valueOf(stack.getDamageValue()));
            }

            if (components.has(DataComponents.UNBREAKABLE)) {
                attrs.put("unbreakable", "true");
            }

            if (components.has(DataComponents.HIDE_TOOLTIP)) {
                attrs.put("hideTooltip", "true");
            }
        } catch (Exception e) {
            LOGGER.warn("提取物品属性失败: {}", e.getMessage());
        }
        return attrs;
    }

    private void extractCustomData(ItemStack stack, Map<String, String> attrs) {
        try {
            CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
            if (customData != null && !customData.isEmpty()) {
                flattenTag(customData.copyTag(), "custom", attrs);
            }
        } catch (Exception e) {
            LOGGER.warn("提取 CustomData 失败: {}", e.getMessage());
        }
    }

    private void extractLore(ItemStack stack, Map<String, String> attrs) {
        try {
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null && lore.lines() != null && !lore.lines().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lore.lines().size(); i++) {
                    if (i > 0) sb.append("\\n");
                    sb.append(lore.lines().get(i).getString());
                }
                attrs.put("lore", sb.toString());
            }
        } catch (Exception e) {
            LOGGER.warn("提取 Lore 失败: {}", e.getMessage());
        }
    }

    private void extractDisplayProperties(ItemStack stack, Map<String, String> attrs) {
        try {
            if (stack.has(DataComponents.RARITY)) {
                attrs.put("rarity", stack.getRarity().name().toLowerCase());
            }
            if (stack.has(DataComponents.FOOD)) {
                attrs.put("food", "true");
            }
            if (stack.has(DataComponents.TOOL)) {
                attrs.put("tool", "true");
            }
            if (stack.has(DataComponents.ATTRIBUTE_MODIFIERS)) {
                attrs.put("hasAttributeModifiers", "true");
            }
        } catch (Exception e) {
            LOGGER.warn("提取显示属性失败: {}", e.getMessage());
        }
    }

    private void flattenTag(net.minecraft.nbt.Tag tag, String prefix, Map<String, String> output) {
        if (output.size() >= MAX_NBT_NODES) {
            if (!output.containsKey("_truncated")) {
                output.put("_truncated", "true");
            }
            return;
        }
        try {
            if (tag instanceof net.minecraft.nbt.CompoundTag compound) {
                for (String key : compound.getAllKeys()) {
                    if (output.size() >= MAX_NBT_NODES) {
                        output.put("_truncated", "true");
                        return;
                    }
                    String childPath = prefix.isEmpty() ? key : prefix + "." + key;
                    net.minecraft.nbt.Tag child = compound.get(key);
                    if (child != null) {
                        flattenTag(child, childPath, output);
                    }
                }
            } else if (tag instanceof net.minecraft.nbt.CollectionTag<?> collection) {
                output.put(prefix, "[list:" + collection.size() + "]");
            } else {
                output.put(prefix, tag.toString());
            }
        } catch (Exception e) {
            output.put(prefix, "[error:" + e.getMessage() + "]");
        }
    }
}
