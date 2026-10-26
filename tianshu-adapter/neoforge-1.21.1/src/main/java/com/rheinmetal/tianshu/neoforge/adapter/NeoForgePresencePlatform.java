package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.client.host.ClientGameContextProvider;
import com.rheinmetal.tianshu.neoforge.adapter.ClientLanguagePolicy;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceEquipment;
import com.rheinmetal.tianshu.client.presence.model.PresenceInventoryItem;
import com.rheinmetal.tianshu.client.presence.model.PresencePlayerStatus;
import com.rheinmetal.tianshu.client.presence.model.PresencePotionEffect;
import com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind;
import com.rheinmetal.tianshu.client.presence.model.PresenceTargetSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceWorldEnvironment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class NeoForgePresencePlatform implements ClientGameContextProvider {
    private final NeoForgePresenceScreenClassifier screenClassifier = new NeoForgePresenceScreenClassifier();
    private final ClientLanguageSnapshot languageSnapshot;
    private final LogSink logSink;

    public NeoForgePresencePlatform(ClientLanguageSnapshot languageSnapshot) {
        this(languageSnapshot, LogSink.NOOP);
    }

    public NeoForgePresencePlatform(ClientLanguageSnapshot languageSnapshot, LogSink logSink) {
        this.languageSnapshot = java.util.Objects.requireNonNull(languageSnapshot, "languageSnapshot");
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
    }

    @Override
    public PresenceContextSnapshot captureContext(Set<PresenceContextGroup> groups) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return PresenceContextSnapshot.empty();
        }

        Set<PresenceContextGroup> requested = groups == null || groups.isEmpty()
                ? Set.of(PresenceContextGroup.INTERACTION_CONTEXT)
                : groups;
        boolean live = requested.contains(PresenceContextGroup.INTERACTION_CONTEXT);
        String languageCode = languageSnapshot.languageCode();
        Screen screen = live ? minecraft.screen : null;
        PresenceScreenKind screenKind = live ? screenClassifier.classify(screen) : PresenceScreenKind.NONE;
        PresenceTargetSnapshot crosshairTarget = live ? crosshairTarget(minecraft, player) : PresenceTargetSnapshot.empty();
        String dimensionId = minecraft.level.dimension().location().toString();
        String heldItemId = live ? itemId(player.getMainHandItem()) : "";
        // Display names come from the game's own translatable registry names. The client language policy is the only
        // place that knows how to ask for them, so this platform keeps no name table of its own: a missing translation
        // degrades to the same readable form the policy already uses.
        String dimensionDisplayName = ClientLanguagePolicy.registryDisplayName(
                minecraft.level.dimension().location(), "dimension", languageCode);
        String heldItemDisplayName = live
                ? ClientLanguagePolicy.itemDisplayName(player.getMainHandItem(),
                        BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()), languageCode)
                : "";
        List<String> equippedItems = live ? equippedItemIds(player) : List.of();
        PresenceEquipment equipment = requested.contains(PresenceContextGroup.PLAYER_EQUIPMENT)
                ? equipment(player, languageCode)
                : PresenceEquipment.empty();

        return new PresenceContextSnapshot(
                player.getStringUUID(),
                dimensionId,
                dimensionDisplayName,
                screenKind,
                heldItemId,
                heldItemDisplayName,
                equippedItems,
                crosshairTarget,
                live && minecraft.options.keyUse.isDown(),
                live && minecraft.options.keyAttack.isDown(),
                live && player.isShiftKeyDown(),
                requested.contains(PresenceContextGroup.PLAYER_STATUS) ? playerStatus(player) : PresencePlayerStatus.empty(),
                requested.contains(PresenceContextGroup.WORLD_ENVIRONMENT) ? worldEnvironment(minecraft.level, player, languageCode) : PresenceWorldEnvironment.empty(),
                requested.contains(PresenceContextGroup.PLAYER_INVENTORY) ? inventoryItems(player, languageCode) : List.of(),
                equipment,
                requested.contains(PresenceContextGroup.PLAYER_ACTIVE_EFFECTS) ? activeEffects(player, languageCode) : List.of(),
                System.currentTimeMillis()
        );
    }

    private List<String> equippedItemIds(Player player) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        addItemId(player.getMainHandItem(), ids);
        addItemId(player.getOffhandItem(), ids);
        for (ItemStack stack : player.getArmorSlots()) {
            addItemId(stack, ids);
        }
        return List.copyOf(ids);
    }

    private PresencePlayerStatus playerStatus(Player player) {
        return new PresencePlayerStatus(
                player.getHealth(),
                player.getMaxHealth(),
                player.getFoodData().getFoodLevel(),
                player.experienceLevel
        );
    }

    private PresenceWorldEnvironment worldEnvironment(Level level, Player player, String languageCode) {
        long dayTime = level.getDayTime() % 24000L;
        String biomeId = "";
        String biomeDisplayName = "";
        try {
            biomeId = level.getBiome(player.blockPosition()).unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("");
            biomeDisplayName = level.getBiome(player.blockPosition()).unwrapKey()
                    .map(key -> ClientLanguagePolicy.registryDisplayName(key.location(), "biome", languageCode))
                    .orElse(biomeId);
        } catch (RuntimeException exception) {
            logSink.warn("neoforge.presence.biome_capture_failed detail=" + exception.getMessage());
        }
        return new PresenceWorldEnvironment(level.isRaining(), level.isThundering(), dayTime, biomeId, biomeDisplayName);
    }

    private List<PresenceInventoryItem> inventoryItems(Player player, String languageCode) {
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return List.of();
        }
        List<PresenceInventoryItem> result = new ArrayList<>();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            ItemStack stack = inventory.getItem(index);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String itemId = itemKey == null ? stack.getItemHolder().getRegisteredName() : itemKey.toString();
            result.add(new PresenceInventoryItem(
                    itemId,
                    ClientLanguagePolicy.itemDisplayName(stack, itemKey, languageCode),
                    stack.getCount(),
                    stack.getMaxStackSize()
            ));
        }
        return List.copyOf(result);
    }

    /**
     * Worn and held items plus the armour numbers the game reports.
     *
     * <p>An empty slot stays {@code null} instead of becoming a zero-count stack, so a consumer can tell "no
     * chestplate" from "a chestplate with nothing in it" — those are different things to say to the player.</p>
     *
     * <p>Armour toughness is read through the attribute lookup rather than assumed present: a modded or
     * custom entity may not carry that attribute, and a missing attribute means zero toughness, not a crash
     * while building a prompt fact.</p>
     */
    private PresenceEquipment equipment(Player player, String languageCode) {
        return new PresenceEquipment(
                equipmentItem(player.getItemBySlot(EquipmentSlot.HEAD), languageCode),
                equipmentItem(player.getItemBySlot(EquipmentSlot.CHEST), languageCode),
                equipmentItem(player.getItemBySlot(EquipmentSlot.LEGS), languageCode),
                equipmentItem(player.getItemBySlot(EquipmentSlot.FEET), languageCode),
                equipmentItem(player.getMainHandItem(), languageCode),
                equipmentItem(player.getOffhandItem(), languageCode),
                player.getArmorValue(),
                armorToughness(player)
        );
    }

    private PresenceInventoryItem equipmentItem(ItemStack stack, String languageCode) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String itemId = itemKey == null ? stack.getItemHolder().getRegisteredName() : itemKey.toString();
        return new PresenceInventoryItem(
                itemId,
                ClientLanguagePolicy.itemDisplayName(stack, itemKey, languageCode),
                stack.getCount(),
                stack.getMaxStackSize()
        );
    }

    private float armorToughness(Player player) {
        var attribute = player.getAttribute(Attributes.ARMOR_TOUGHNESS);
        return attribute == null ? 0.0F : (float) attribute.getValue();
    }
    private List<PresencePotionEffect> activeEffects(Player player, String languageCode) {
        List<PresencePotionEffect> result = new ArrayList<>();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            try {
                ResourceLocation effectId = effect.getEffect().unwrapKey().map(key -> key.location()).orElse(null);
                result.add(new PresencePotionEffect(
                        effectId == null ? "" : effectId.toString(),
                        ClientLanguagePolicy.effectDisplayName(effectId, effect.getEffect().value().getDescriptionId(), languageCode),
                        effect.getDuration(),
                        effect.getAmplifier(),
                        effect.getEffect().value().isBeneficial()
                ));
            } catch (RuntimeException exception) {
                logSink.warn("neoforge.presence.effect_capture_failed detail=" + exception.getMessage());
            }
        }
        return List.copyOf(result);
    }

    private void addItemId(ItemStack stack, LinkedHashSet<String> ids) {
        String id = itemId(stack);
        if (!id.isBlank()) {
            ids.add(id);
        }
    }

    private String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    private PresenceTargetSnapshot crosshairTarget(Minecraft minecraft, Player player) {
        HitResult hitResult = minecraft.hitResult;
        if (!(hitResult instanceof EntityHitResult entityHitResult)) {
            return PresenceTargetSnapshot.empty();
        }
        Entity entity = entityHitResult.getEntity();
        ResourceLocation typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return new PresenceTargetSnapshot(
                entity.getStringUUID(),
                typeKey == null ? "" : typeKey.toString(),
                entity.getDisplayName().getString(),
                player.distanceTo(entity),
                true
        );
    }
}
