package com.rheinmetal.tianshu.neoforge.adapter;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.client.host.ClientGameContextProvider;
import com.rheinmetal.tianshu.neoforge.adapter.ClientLanguagePolicy;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceInputKind;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NeoForgePresencePlatform implements ClientGameContextProvider {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final NeoForgePresenceScreenClassifier screenClassifier = new NeoForgePresenceScreenClassifier();

    @Override
    public PresenceContextSnapshot captureContext(Set<PresenceContextGroup> groups, PresenceInputKind inputKind) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return PresenceContextSnapshot.empty();
        }

        Set<PresenceContextGroup> requested = groups == null || groups.isEmpty()
                ? Set.of(PresenceContextGroup.INTERACTION_CONTEXT)
                : groups;
        boolean live = requested.contains(PresenceContextGroup.INTERACTION_CONTEXT);
        Screen screen = minecraft.screen;
        PresenceScreenKind screenKind = screenClassifier.classify(screen);
        PresenceTargetSnapshot crosshairTarget = crosshairTarget(minecraft, player);
        String dimensionId = minecraft.level.dimension().location().toString();
        String heldItemId = itemId(player.getMainHandItem());
        List<String> equippedItems = equippedItemIds(player);
        Map<String, String> facts = facts(screenKind);

        return new PresenceContextSnapshot(
                player.getStringUUID(),
                dimensionId,
                screenKind,
                screen == null ? "" : screen.getClass().getSimpleName(),
                heldItemId,
                equippedItems,
                crosshairTarget,
                minecraft.options.keyUse.isDown(),
                minecraft.options.keyAttack.isDown(),
                player.isShiftKeyDown(),
                inputKind == null ? PresenceInputKind.NONE : inputKind,
                requested.contains(PresenceContextGroup.PLAYER_STATUS) ? playerStatus(player) : PresencePlayerStatus.empty(),
                requested.contains(PresenceContextGroup.WORLD_ENVIRONMENT) ? worldEnvironment(minecraft.level, player) : PresenceWorldEnvironment.empty(),
                requested.contains(PresenceContextGroup.PLAYER_INVENTORY) ? inventoryItems(player) : List.of(),
                requested.contains(PresenceContextGroup.PLAYER_ACTIVE_EFFECTS) ? activeEffects(player) : List.of(),
                live ? facts : Map.of(),
                System.currentTimeMillis()
        );
    }

    private Map<String, String> facts(PresenceScreenKind screenKind) {
        if (screenKind == PresenceScreenKind.NONE) {
            return Map.of();
        }
        return Map.of("screen", screenKind.name().toLowerCase(java.util.Locale.ROOT));
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

    private PresenceWorldEnvironment worldEnvironment(Level level, Player player) {
        long dayTime = level.getDayTime() % 24000L;
        String biomeId = "";
        String biomeDisplayName = "";
        try {
            biomeId = level.getBiome(player.blockPosition()).unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("");
            biomeDisplayName = level.getBiome(player.blockPosition()).unwrapKey()
                    .map(key -> ClientLanguagePolicy.registryDisplayName(key.location(), "biome"))
                    .orElse(biomeId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Presence failed to read biome: {}", exception.getMessage());
        }
        return new PresenceWorldEnvironment(level.isRaining(), level.isThundering(), dayTime, biomeId, biomeDisplayName);
    }

    private List<PresenceInventoryItem> inventoryItems(Player player) {
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
                    ClientLanguagePolicy.itemDisplayName(stack, itemKey),
                    stack.getCount(),
                    stack.getMaxStackSize()
            ));
        }
        return List.copyOf(result);
    }

    private List<PresencePotionEffect> activeEffects(Player player) {
        List<PresencePotionEffect> result = new ArrayList<>();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            try {
                ResourceLocation effectId = effect.getEffect().unwrapKey().map(key -> key.location()).orElse(null);
                result.add(new PresencePotionEffect(
                        effectId == null ? "" : effectId.toString(),
                        ClientLanguagePolicy.effectDisplayName(effectId, effect.getEffect().value().getDescriptionId()),
                        effect.getDuration(),
                        effect.getAmplifier(),
                        effect.getEffect().value().isBeneficial()
                ));
            } catch (RuntimeException exception) {
                LOGGER.warn("Presence failed to read active effect: {}", exception.getMessage());
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
