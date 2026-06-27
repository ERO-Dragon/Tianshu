package com.rheinmetal.tianshu.client.presence.capture;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.client.language.ClientLanguagePolicy;
import com.rheinmetal.tianshu.client.presence.PresenceStateStore;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceInputKind;
import com.rheinmetal.tianshu.client.presence.model.PresenceInventoryItem;
import com.rheinmetal.tianshu.client.presence.model.PresencePlayerStatus;
import com.rheinmetal.tianshu.client.presence.model.PresencePotionEffect;
import com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind;
import com.rheinmetal.tianshu.client.presence.model.PresenceTargetSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceWorldEnvironment;
import com.rheinmetal.tianshu.protocol.payload.PresenceChatMessagePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
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
import java.util.UUID;

public final class PresenceEventCollector {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final PresenceStateStore stateStore;
    private final PresenceScreenClassifier screenClassifier = new PresenceScreenClassifier();
    private final PresenceAdvancementTracker advancementTracker = new PresenceAdvancementTracker();
    private PresenceWorldEventSink worldEventSink = PresenceWorldEventSink.NOOP;
    private PresenceChatMessageSink chatMessageSink = PresenceChatMessageSink.NOOP;
    private long lastKeyboardEventAtMillis;
    private long lastMouseEventAtMillis;
    private long nextDetailedRefreshAtMillis;
    private boolean detailedDirty = true;

    public PresenceEventCollector(PresenceStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public void setWorldEventSink(PresenceWorldEventSink worldEventSink) {
        this.worldEventSink = worldEventSink == null ? PresenceWorldEventSink.NOOP : worldEventSink;
    }

    public void setChatMessageSink(PresenceChatMessageSink chatMessageSink) {
        this.chatMessageSink = chatMessageSink == null ? PresenceChatMessageSink.NOOP : chatMessageSink;
    }

    public void tick() {
        PresenceContextSnapshot snapshot = captureLiveSnapshot(PresenceInputKind.NONE);
        stateStore.updateContext(snapshot);
        refreshDetailedIfNeeded(PresenceInputKind.NONE);
    }

    public void recordScreenChanged(Screen screen) {
        stateStore.updateContext(captureLiveSnapshot(PresenceInputKind.NONE));
        markDetailedDirty();
    }

    public void recordKeyboardInput() {
        long now = System.currentTimeMillis();
        if (now - lastKeyboardEventAtMillis < PresenceRefreshPolicy.INPUT_EVENT_MIN_INTERVAL_MILLIS) {
            return;
        }
        lastKeyboardEventAtMillis = now;
        stateStore.updateContext(captureLiveSnapshot(PresenceInputKind.KEYBOARD));
    }

    public void recordMouseInput() {
        long now = System.currentTimeMillis();
        if (now - lastMouseEventAtMillis < PresenceRefreshPolicy.INPUT_EVENT_MIN_INTERVAL_MILLIS) {
            return;
        }
        lastMouseEventAtMillis = now;
        stateStore.updateContext(captureLiveSnapshot(PresenceInputKind.MOUSE));
    }

    public void recordVoiceKeyInput() {
        stateStore.updateContext(captureLiveSnapshot(PresenceInputKind.VOICE_KEY));
    }

    private void refreshDetailedNow(PresenceInputKind inputKind) {
        PresenceContextSnapshot snapshot = captureLiveSnapshot(inputKind, true);
        stateStore.updateDetailedContext(snapshot);
        detailedDirty = false;
        nextDetailedRefreshAtMillis = System.currentTimeMillis() + PresenceRefreshPolicy.DETAILED_REFRESH_INTERVAL_MILLIS;
    }

    public void recordPlayerChatMessage(Component message, UUID senderId, String senderName) {
        if (message == null || senderId == null) {
            return;
        }
        String text = message.getString();
        if (text == null || text.isBlank()) {
            return;
        }
        String sender = cleanSenderName(senderName);
        chatMessageSink.publish(new PresenceChatMessagePayload(
                senderId.toString(),
                sender,
                text
        ));
        markDetailedDirty();
    }

    public void recordAdvancementUpdate(ClientboundUpdateAdvancementsPacket packet) {
        for (var payload : advancementTracker.collect(packet)) {
            worldEventSink.publish(payload);
        }
    }

    private void refreshDetailedIfNeeded(PresenceInputKind inputKind) {
        long now = System.currentTimeMillis();
        if (!detailedDirty && now < nextDetailedRefreshAtMillis) {
            return;
        }
        refreshDetailedNow(inputKind);
    }

    private void markDetailedDirty() {
        detailedDirty = true;
    }

    private PresenceContextSnapshot captureLiveSnapshot(PresenceInputKind inputKind) {
        return captureLiveSnapshot(inputKind, false);
    }

    private PresenceContextSnapshot captureLiveSnapshot(PresenceInputKind inputKind, boolean detailed) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return PresenceContextSnapshot.empty();
        }

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
                player.isShiftKeyDown(),
                inputKind == null ? PresenceInputKind.NONE : inputKind,
                detailed ? playerStatus(player) : PresencePlayerStatus.empty(),
                detailed ? worldEnvironment(minecraft.level, player) : PresenceWorldEnvironment.empty(),
                detailed ? inventoryItems(player) : List.of(),
                detailed ? activeEffects(player) : List.of(),
                facts,
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

    private String cleanSenderName(String senderName) {
        return senderName == null ? "" : senderName.trim();
    }

}
