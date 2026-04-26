package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IInventoryDataProvider;
import com.rheinmetal.tianshu.snapshot.InventorySnapshot;
import com.rheinmetal.tianshu.snapshot.ItemSnapshot;
import com.rheinmetal.tianshu.snapshot.MatchedSlotData;
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
import org.slf4j.Logger;

import java.util.*;

public class NeoForgeInventoryProvider implements IInventoryDataProvider {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_NBT_NODES = 50;

    @Override
    public ItemSnapshot getMainHandItemData() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        return toItemSnapshot(mc.player.getMainHandItem(), mc.player.getInventory().selected);
    }

    @Override
    public InventorySnapshot getAllInventoryItemsData() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return new InventorySnapshot(Collections.emptyList(), -1, null, null, Collections.emptyList());

        Player player = mc.player;
        Inventory inv = player.getInventory();
        List<ItemSnapshot> items = new ArrayList<>();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                items.add(toItemSnapshot(stack, i));
            }
        }

        ItemSnapshot mainHand = toItemSnapshot(player.getMainHandItem(), inv.selected);
        ItemSnapshot offHand = toItemSnapshot(player.getItemBySlot(EquipmentSlot.OFFHAND), Inventory.SLOT_OFFHAND);

        List<ItemSnapshot> armor = new ArrayList<>();
        armor.add(toItemSnapshot(player.getItemBySlot(EquipmentSlot.HEAD), -1));
        armor.add(toItemSnapshot(player.getItemBySlot(EquipmentSlot.CHEST), -1));
        armor.add(toItemSnapshot(player.getItemBySlot(EquipmentSlot.LEGS), -1));
        armor.add(toItemSnapshot(player.getItemBySlot(EquipmentSlot.FEET), -1));

        return new InventorySnapshot(items, inv.selected, mainHand, offHand, armor);
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

        return new ItemSnapshot(slotIndex, itemId, displayName, count, maxDamage, damage, durabilityPercent, enchantments, attributes);
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
