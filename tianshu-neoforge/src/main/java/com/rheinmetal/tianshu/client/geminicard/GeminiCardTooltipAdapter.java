package com.rheinmetal.tianshu.client.geminicard;

import com.rheinmetal.tianshu.core.FeatureManager;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardComparisonData;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardContext;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardItemData;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardItemKind;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardLine;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardLineTone;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardMechanismKey;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class GeminiCardTooltipAdapter {

    private static final int MAX_EXTRA_LINES = 8;
    private static final int MAX_LINE_CHARS = 80;
    private static final com.rheinmetal.tianshu.function.GeminiCard.GeminiCardEngine CORE = new com.rheinmetal.tianshu.function.GeminiCard.GeminiCardEngine();

    private GeminiCardTooltipAdapter() {
    }

    public static void appendTooltipLines(ItemStack hoveredStack, List<FormattedText> tooltipLines, Player player) {
        if (tooltipLines == null) {
            return;
        }
        if (hoveredStack == null || hoveredStack.isEmpty() || !FeatureManager.isCompanionCardEnabled()) {
            CORE.killActiveSession();
            return;
        }

        GeminiCardContext context = buildContext(hoveredStack, player);
        List<GeminiCardLine> lines = CORE.buildLines(context);
        Set<String> appendedTexts = new HashSet<>();
        int appended = 0;
        for (GeminiCardLine line : lines) {
            if (line == null || line.text() == null || line.text().isBlank()) {
                continue;
            }
            String text = normalizeTooltipText(line.text());
            if (!appendedTexts.add(text)) {
                continue;
            }
            tooltipLines.add(Component.literal(text).withStyle(styleFor(line.tone())));
            appended++;
            if (appended >= MAX_EXTRA_LINES) {
                break;
            }
        }
    }

    public static void killActiveSession() {
        CORE.killActiveSession();
    }

    private static GeminiCardContext buildContext(ItemStack hoveredStack, Player player) {
        GeminiCardItemData hoveredItem = toItemData(hoveredStack);
        GeminiCardComparisonData comparison = resolveComparison(hoveredStack, player);
        return new GeminiCardContext(true, hoveredItem, comparison);
    }

    private static GeminiCardComparisonData resolveComparison(ItemStack hoveredStack, Player player) {
        if (player == null || hoveredStack == null || hoveredStack.isEmpty()) {
            return null;
        }

        if (isEquipment(hoveredStack)) {
            EquipmentSlot slot = equipmentSlotFor(hoveredStack);
            if (slot != null) {
                ItemStack equipped = player.getItemBySlot(slot);
                if (!equipped.isEmpty() && equipped != hoveredStack && isSamePrimaryType(hoveredStack, equipped)) {
                    return new GeminiCardComparisonData(toItemData(equipped), equipmentLabel(slot));
                }
            }
            GeminiCardComparisonData handFallback = resolveHandComparison(hoveredStack, player, "当前手持装备");
            if (handFallback != null) {
                return handFallback;
            }
            return null;
        }

        if (isHandComparedItem(hoveredStack)) {
            return resolveHandComparison(hoveredStack, player, null);
        }

        return null;
    }

    private static GeminiCardComparisonData resolveHandComparison(ItemStack hoveredStack, Player player, String genericLabel) {
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty() && mainHand != hoveredStack && isSamePrimaryType(hoveredStack, mainHand)) {
            return new GeminiCardComparisonData(toItemData(mainHand), genericLabel == null ? "当前主手" : genericLabel);
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && offhand != hoveredStack && isSamePrimaryType(hoveredStack, offhand)) {
            return new GeminiCardComparisonData(toItemData(offhand), genericLabel == null ? "当前副手" : genericLabel);
        }
        return null;
    }

    private static GeminiCardItemData toItemData(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new GeminiCardItemData("", "", GeminiCardItemKind.OTHER, true, false, 0, 0, 0.0D, 0.0D, 0.0D, Map.of(), Map.of());
        }

        String itemId = stack.getItemHolder().getRegisteredName();
        GeminiCardItemKind kind = toKind(stack);
        boolean damageable = stack.isDamageableItem();
        int maxDamage = stack.getMaxDamage();
        int damage = stack.getDamageValue();
        EquipmentSlot attributeSlot = attributeSlotFor(stack);
        double attackDamage = readAttribute(stack, "attack_damage", attributeSlot);
        double attackSpeed = readAttribute(stack, "attack_speed", attributeSlot);
        double armor = readAttribute(stack, "armor", attributeSlot);
        Map<String, Integer> enchantments = readEnchantments(stack);
        Map<String, String> mechanisms = readMechanisms(stack);
        String semanticKey = buildSemanticKey(stack, itemId, enchantments, attackDamage, attackSpeed, armor, mechanisms);

        return new GeminiCardItemData(
                semanticKey,
                itemId,
                kind,
                false,
                damageable,
                maxDamage,
                damage,
                attackDamage,
                attackSpeed,
                armor,
                enchantments,
                mechanisms
        );
    }

    private static GeminiCardItemKind toKind(ItemStack stack) {
        if (isEquipment(stack)) return GeminiCardItemKind.EQUIPMENT;
        if (isWeapon(stack)) return GeminiCardItemKind.WEAPON;
        if (isTool(stack)) return GeminiCardItemKind.TOOL;
        return GeminiCardItemKind.OTHER;
    }

    private static boolean isEquipment(ItemStack stack) {
        return stack.getItem() instanceof ArmorItem
                || stack.is(ItemTags.HEAD_ARMOR)
                || stack.is(ItemTags.CHEST_ARMOR)
                || stack.is(ItemTags.LEG_ARMOR)
                || stack.is(ItemTags.FOOT_ARMOR)
                || stack.is(ItemTags.ARMOR_ENCHANTABLE);
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.is(ItemTags.WEAPON_ENCHANTABLE)
                || stack.is(ItemTags.SWORD_ENCHANTABLE)
                || stack.is(ItemTags.SHARP_WEAPON_ENCHANTABLE)
                || stack.is(ItemTags.BOW_ENCHANTABLE)
                || stack.is(ItemTags.CROSSBOW_ENCHANTABLE)
                || stack.is(ItemTags.TRIDENT_ENCHANTABLE)
                || stack.is(ItemTags.MACE_ENCHANTABLE);
    }

    private static boolean isTool(ItemStack stack) {
        return stack.is(ItemTags.MINING_ENCHANTABLE)
                || stack.is(ItemTags.MINING_LOOT_ENCHANTABLE)
                || stack.is(ItemTags.PICKAXES)
                || stack.is(ItemTags.AXES)
                || stack.is(ItemTags.SHOVELS)
                || stack.is(ItemTags.HOES)
                || stack.getItem() instanceof ShearsItem;
    }

    private static boolean isHandComparedItem(ItemStack stack) {
        GeminiCardItemKind kind = toKind(stack);
        return kind == GeminiCardItemKind.TOOL || kind == GeminiCardItemKind.WEAPON || kind == GeminiCardItemKind.EQUIPMENT;
    }

    private static boolean isSamePrimaryType(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return toKind(a) == toKind(b) && toKind(a) != GeminiCardItemKind.OTHER;
    }

    private static EquipmentSlot attributeSlotFor(ItemStack stack) {
        EquipmentSlot equipmentSlot = equipmentSlotFor(stack);
        if (equipmentSlot != null) {
            return equipmentSlot;
        }
        if (isHandComparedItem(stack)) {
            return EquipmentSlot.MAINHAND;
        }
        return null;
    }

    private static EquipmentSlot equipmentSlotFor(ItemStack stack) {
        if (stack.getItem() instanceof ArmorItem armorItem) {
            return armorItem.getEquipmentSlot();
        }
        if (stack.is(ItemTags.HEAD_ARMOR)) {
            return EquipmentSlot.HEAD;
        }
        if (stack.is(ItemTags.CHEST_ARMOR)) {
            return EquipmentSlot.CHEST;
        }
        if (stack.is(ItemTags.LEG_ARMOR)) {
            return EquipmentSlot.LEGS;
        }
        if (stack.is(ItemTags.FOOT_ARMOR)) {
            return EquipmentSlot.FEET;
        }
        return null;
    }

    private static double readAttribute(ItemStack stack, String attributeKey, EquipmentSlot slot) {
        double value = 0.0D;
        if (slot == null) {
            return value;
        }
        try {
            ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (modifiers == null) {
                return value;
            }
            for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
                if (!entry.slot().test(slot)) {
                    continue;
                }
                String attrId = entry.attribute().unwrapKey().map(key -> key.location().toString()).orElse("");
                if (attrId.contains(attributeKey)) {
                    value += entry.modifier().amount();
                }
            }
        } catch (Exception ignored) {
        }
        return value;
    }

    private static Map<String, Integer> readEnchantments(ItemStack stack) {
        Map<String, Integer> result = new TreeMap<>();
        try {
            ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
            if (enchantments == null) {
                return result;
            }
            for (var entry : enchantments.entrySet()) {
                String id = entry.getKey().unwrapKey().map(key -> key.location().toString()).orElse("unknown");
                result.put(id, entry.getIntValue());
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static Map<String, String> readMechanisms(ItemStack stack) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(GeminiCardMechanismKey.REPAIRABLE.label(), String.valueOf(stack.isDamageableItem()));
        values.put(GeminiCardMechanismKey.UPGRADEABLE.label(), String.valueOf(stack.has(DataComponents.ENCHANTMENTS) || stack.isEnchantable()));
        values.put(GeminiCardMechanismKey.GROWABLE.label(), "未知");
        values.put(GeminiCardMechanismKey.CHARGEABLE.label(), String.valueOf(stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem || stack.getItem() instanceof TridentItem));
        values.put(GeminiCardMechanismKey.RESOURCE_COST.label(), stack.has(DataComponents.FOOD) ? "食物" : "未知");
        values.put(GeminiCardMechanismKey.SKILL_BINDING.label(), "未知");
        values.put(GeminiCardMechanismKey.TRIGGER_CONDITION.label(), "未知");
        values.put(GeminiCardMechanismKey.USAGE_LIMITATION.label(), stack.has(DataComponents.UNBREAKABLE) ? "不可损坏" : "未知");
        values.put(GeminiCardMechanismKey.DURABILITY_MECHANISM.label(), stack.isDamageableItem() ? stack.getDamageValue() + "/" + stack.getMaxDamage() : "无");
        values.put(GeminiCardMechanismKey.DEATH_PENALTY.label(), "未知");
        return values;
    }

    private static String buildSemanticKey(ItemStack stack, String itemId, Map<String, Integer> enchantments, double attackDamage, double attackSpeed, double armor, Map<String, String> mechanisms) {
        StringBuilder builder = new StringBuilder();
        builder.append(itemId).append('|');
        builder.append(stack.getDamageValue()).append('/').append(stack.getMaxDamage()).append('|');
        builder.append(enchantments).append('|');
        builder.append(attackDamage).append('|');
        builder.append(attackSpeed).append('|');
        builder.append(armor).append('|');
        builder.append(readLore(stack)).append('|');
        builder.append(String.valueOf(stack.getComponents()).hashCode()).append('|');
        builder.append(mechanisms);
        return Integer.toHexString(builder.toString().hashCode());
    }

    private static String readLore(ItemStack stack) {
        try {
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null || lore.lines().isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (Component line : lore.lines()) {
                builder.append(line.getString()).append('\n');
            }
            return builder.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeTooltipText(String text) {
        String normalized = text.replace('\n', ' ').strip();
        if (normalized.length() <= MAX_LINE_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_LINE_CHARS - 1) + "…";
    }

    private static ChatFormatting styleFor(GeminiCardLineTone tone) {
        if (tone == null) {
            return ChatFormatting.WHITE;
        }
        return switch (tone) {
            case MUTED -> ChatFormatting.GRAY;
            case POSITIVE -> ChatFormatting.GREEN;
            case NEGATIVE -> ChatFormatting.RED;
            case HEADER -> ChatFormatting.DARK_GRAY;
            default -> ChatFormatting.WHITE;
        };
    }

    private static String equipmentLabel(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "当前头盔";
            case CHEST -> "当前胸甲";
            case LEGS -> "当前护腿";
            case FEET -> "当前靴子";
            case MAINHAND -> "当前主手";
            case OFFHAND -> "当前副手";
            default -> "当前装备";
        };
    }
}
