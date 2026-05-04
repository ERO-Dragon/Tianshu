package com.rheinmetal.tianshu.client.geminicard;

import com.rheinmetal.tianshu.core.FeatureManager;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardComparisonData;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardContext;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardHoverPayload;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardItemData;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardItemKind;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardLine;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardLineTone;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardMechanismKey;
import com.rheinmetal.tianshu.function.GeminiCard.GeminiCardProtocolAdapter;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public final class GeminiCardTooltipAdapter {

    private static final int MAX_EXTRA_LINES = 8;
    private static final int MAX_LINE_CHARS = 80;
    private static final List<TagKey<Item>> COMPARISON_TAGS = List.of(
            ItemTags.HEAD_ARMOR,
            ItemTags.CHEST_ARMOR,
            ItemTags.LEG_ARMOR,
            ItemTags.FOOT_ARMOR,
            ItemTags.SWORD_ENCHANTABLE,
            ItemTags.SHARP_WEAPON_ENCHANTABLE,
            ItemTags.BOW_ENCHANTABLE,
            ItemTags.CROSSBOW_ENCHANTABLE,
            ItemTags.TRIDENT_ENCHANTABLE,
            ItemTags.MACE_ENCHANTABLE,
            ItemTags.PICKAXES,
            ItemTags.AXES,
            ItemTags.SHOVELS,
            ItemTags.HOES,
            ItemTags.MINING_ENCHANTABLE,
            ItemTags.MINING_LOOT_ENCHANTABLE
    );
    private static final com.rheinmetal.tianshu.function.GeminiCard.GeminiCardEngine CORE = new com.rheinmetal.tianshu.function.GeminiCard.GeminiCardEngine();
    private static final long HOVER_STABLE_TRIGGER_MS = 2_000L;
    private static GeminiCardProtocolAdapter protocolAdapter;
    private static long lastTooltipFrameMs = 0L;
    private static long activeSemanticSinceMs = 0L;
    private static String lastTooltipSemanticKey = "";
    private static String lastPublishedStableSemanticKey = "";

    private GeminiCardTooltipAdapter() {
    }

    public static void configureProtocol(ProtocolRuntime runtime) {
        protocolAdapter = runtime == null ? null : new GeminiCardProtocolAdapter(runtime);
    }

    public static void appendTooltipLines(ItemStack hoveredStack, List<FormattedText> tooltipLines, Player player) {
        appendTooltipLinesInternal(hoveredStack, tooltipLines, player);
    }

    public static void appendComponentTooltipLines(ItemStack hoveredStack, List<Component> tooltipLines, Player player) {
        appendTooltipLinesInternal(hoveredStack, tooltipLines, player);
    }

    private static void appendTooltipLinesInternal(ItemStack hoveredStack, List<? super Component> tooltipLines, Player player) {
        if (tooltipLines == null) {
            return;
        }
        if (hoveredStack == null || hoveredStack.isEmpty() || !FeatureManager.isCompanionCardEnabled()) {
            CORE.killActiveSession();
            return;
        }

        GeminiCardContext context = buildContext(hoveredStack, player);
        String semanticKey = context.hoveredItem().semanticKey();
        long now = System.currentTimeMillis();
        if (!Objects.equals(lastTooltipSemanticKey, semanticKey)) {
            activeSemanticSinceMs = now;
            lastPublishedStableSemanticKey = "";
        }
        if (Objects.equals(lastTooltipSemanticKey, semanticKey) && now - lastTooltipFrameMs <= 2L) {
            return;
        }
        lastTooltipFrameMs = now;
        lastTooltipSemanticKey = semanticKey;
        publishHoverStableIfReady(context, now);
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
        lastTooltipFrameMs = 0L;
        activeSemanticSinceMs = 0L;
        lastTooltipSemanticKey = "";
        lastPublishedStableSemanticKey = "";
        CORE.killActiveSession();
        GeminiCardProtocolAdapter adapter = protocolAdapter;
        if (adapter != null) {
            adapter.publishHoverCleared();
        }
    }

    public static void tickLifecycle() {
        long now = System.currentTimeMillis();
        if (lastTooltipFrameMs > 0L && now - lastTooltipFrameMs > 100L) {
            killActiveSession();
        }
    }

    private static void publishHoverStableIfReady(GeminiCardContext context, long now) {
        GeminiCardProtocolAdapter adapter = protocolAdapter;
        if (adapter == null || context == null || context.hoveredItem() == null || context.hoveredItem().empty()) {
            return;
        }
        String semanticKey = context.hoveredItem().semanticKey();
        long stableForMs = now - activeSemanticSinceMs;
        if (stableForMs < HOVER_STABLE_TRIGGER_MS || Objects.equals(lastPublishedStableSemanticKey, semanticKey)) {
            return;
        }
        adapter.publishHoverStable(new GeminiCardHoverPayload(context.hoveredItem(), context.comparison(), stableForMs));
        lastPublishedStableSemanticKey = semanticKey;
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
                if (!equipped.isEmpty() && equipped != hoveredStack && isComparableItem(hoveredStack, equipped)) {
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
        if (!mainHand.isEmpty() && mainHand != hoveredStack && isComparableItem(hoveredStack, mainHand)) {
            return new GeminiCardComparisonData(toItemData(mainHand), genericLabel == null ? "当前主手" : genericLabel);
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && offhand != hoveredStack && isComparableItem(hoveredStack, offhand)) {
            return new GeminiCardComparisonData(toItemData(offhand), genericLabel == null ? "当前副手" : genericLabel);
        }
        return null;
    }

    private static GeminiCardItemData toItemData(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new GeminiCardItemData("", "", GeminiCardItemKind.OTHER, "", true, false, 0, 0, 0.0D, 0.0D, 0.0D, Map.of(), Map.of());
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
        String comparisonKey = buildComparisonKey(stack, kind);
        String semanticKey = buildSemanticKey(stack, itemId, comparisonKey, enchantments, attackDamage, attackSpeed, armor, mechanisms);

        return new GeminiCardItemData(
                semanticKey,
                itemId,
                kind,
                comparisonKey,
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

    private static boolean isComparableItem(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        GeminiCardItemKind kind = toKind(a);
        if (kind == GeminiCardItemKind.OTHER || kind != toKind(b)) {
            return false;
        }
        if (hasSharedComparisonTag(a, b)) {
            return true;
        }
        return kind == GeminiCardItemKind.WEAPON && hasSharedWeaponClass(a, b)
                || kind == GeminiCardItemKind.TOOL && hasSharedToolClass(a, b)
                || kind == GeminiCardItemKind.EQUIPMENT && hasSharedEquipmentSlot(a, b);
    }

    private static boolean hasSharedComparisonTag(ItemStack a, ItemStack b) {
        for (TagKey<Item> tag : COMPARISON_TAGS) {
            if (a.is(tag) && b.is(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSharedWeaponClass(ItemStack a, ItemStack b) {
        return a.getItem() instanceof SwordItem && b.getItem() instanceof SwordItem
                || a.getItem() instanceof BowItem && b.getItem() instanceof BowItem
                || a.getItem() instanceof CrossbowItem && b.getItem() instanceof CrossbowItem
                || a.getItem() instanceof TridentItem && b.getItem() instanceof TridentItem
                || a.getItem() instanceof MaceItem && b.getItem() instanceof MaceItem;
    }

    private static boolean hasSharedToolClass(ItemStack a, ItemStack b) {
        return a.getItem() instanceof PickaxeItem && b.getItem() instanceof PickaxeItem
                || a.getItem() instanceof AxeItem && b.getItem() instanceof AxeItem
                || a.getItem() instanceof ShovelItem && b.getItem() instanceof ShovelItem
                || a.getItem() instanceof HoeItem && b.getItem() instanceof HoeItem
                || a.getItem() instanceof ShearsItem && b.getItem() instanceof ShearsItem;
    }

    private static boolean hasSharedEquipmentSlot(ItemStack a, ItemStack b) {
        EquipmentSlot slotA = equipmentSlotFor(a);
        EquipmentSlot slotB = equipmentSlotFor(b);
        return slotA != null && slotA == slotB;
    }

    private static String buildComparisonKey(ItemStack stack, GeminiCardItemKind kind) {
        if (stack == null || stack.isEmpty() || kind == GeminiCardItemKind.OTHER) {
            return "";
        }
        for (TagKey<Item> tag : COMPARISON_TAGS) {
            if (stack.is(tag)) {
                return kind.name() + ":" + tag.location();
            }
        }
        EquipmentSlot slot = equipmentSlotFor(stack);
        if (slot != null) {
            return kind.name() + ":slot/" + slot.getName();
        }
        if (stack.getItem() instanceof SwordItem) return kind.name() + ":class/sword";
        if (stack.getItem() instanceof BowItem) return kind.name() + ":class/bow";
        if (stack.getItem() instanceof CrossbowItem) return kind.name() + ":class/crossbow";
        if (stack.getItem() instanceof TridentItem) return kind.name() + ":class/trident";
        if (stack.getItem() instanceof MaceItem) return kind.name() + ":class/mace";
        if (stack.getItem() instanceof PickaxeItem) return kind.name() + ":class/pickaxe";
        if (stack.getItem() instanceof AxeItem) return kind.name() + ":class/axe";
        if (stack.getItem() instanceof ShovelItem) return kind.name() + ":class/shovel";
        if (stack.getItem() instanceof HoeItem) return kind.name() + ":class/hoe";
        if (stack.getItem() instanceof ShearsItem) return kind.name() + ":class/shears";
        return "";
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
        value += readStackAttributeModifiers(stack, attributeKey, slot);
        if (Math.abs(value) < 0.001D) {
            value += readItemDefaultAttributeModifiers(stack, attributeKey, slot);
        }
        return value;
    }

    private static double readStackAttributeModifiers(ItemStack stack, String attributeKey, EquipmentSlot slot) {
        double value = 0.0D;
        try {
            ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (modifiers == null) {
                return value;
            }
            value += sumAttributeModifiers(modifiers, attributeKey, slot);
        } catch (Exception ignored) {
        }
        return value;
    }

    private static double readItemDefaultAttributeModifiers(ItemStack stack, String attributeKey, EquipmentSlot slot) {
        try {
            Object item = stack.getItem();
            try {
                Object modifiers = item.getClass().getMethod("getDefaultAttributeModifiers").invoke(item);
                if (modifiers instanceof ItemAttributeModifiers itemAttributeModifiers) {
                    return sumAttributeModifiers(itemAttributeModifiers, attributeKey, slot);
                }
            } catch (NoSuchMethodException ignored) {
            }
            try {
                Object modifiers = item.getClass().getMethod("getDefaultAttributeModifiers", ItemStack.class).invoke(item, stack);
                if (modifiers instanceof ItemAttributeModifiers itemAttributeModifiers) {
                    return sumAttributeModifiers(itemAttributeModifiers, attributeKey, slot);
                }
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Exception ignored) {
        }
        return 0.0D;
    }

    private static double sumAttributeModifiers(ItemAttributeModifiers modifiers, String attributeKey, EquipmentSlot slot) {
        double value = 0.0D;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (!entry.slot().test(slot)) {
                continue;
            }
            String attrId = entry.attribute().unwrapKey().map(key -> key.location().toString()).orElse("");
            if (attrId.contains(attributeKey)) {
                value += entry.modifier().amount();
            }
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

    private static String buildSemanticKey(ItemStack stack, String itemId, String comparisonKey, Map<String, Integer> enchantments, double attackDamage, double attackSpeed, double armor, Map<String, String> mechanisms) {
        StringBuilder builder = new StringBuilder();
        builder.append(itemId).append('|');
        builder.append(comparisonKey).append('|');
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
