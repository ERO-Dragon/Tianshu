package com.rheinmetal.tianshu.platform;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class NeoForgePresenceAdvancementTracker {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Set<String> completedAdvancementIds = new HashSet<>();
    private boolean baselineInitialized;

    List<PresenceWorldEventPayload> collect(ClientboundUpdateAdvancementsPacket packet) {
        if (packet == null) {
            return List.of();
        }
        if (packet.shouldReset()) {
            completedAdvancementIds.clear();
            baselineInitialized = false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Map<ResourceLocation, AdvancementProgress> progressById = packet.getProgress();
        if (progressById == null || progressById.isEmpty()) {
            if (!baselineInitialized) {
                baselineInitialized = true;
            }
            return List.of();
        }

        if (!baselineInitialized) {
            rememberCompleted(progressById);
            baselineInitialized = true;
            return List.of();
        }

        List<PresenceWorldEventPayload> result = new ArrayList<>();
        for (Map.Entry<ResourceLocation, AdvancementProgress> entry : progressById.entrySet()) {
            ResourceLocation advancementId = entry.getKey();
            AdvancementProgress progress = entry.getValue();
            if (advancementId == null || progress == null) {
                continue;
            }
            String id = advancementId.toString();
            if (!progress.isDone()) {
                completedAdvancementIds.remove(id);
                continue;
            }
            if (!completedAdvancementIds.add(id)) {
                continue;
            }
            payload(minecraft, packet, advancementId, progress).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    private void rememberCompleted(Map<ResourceLocation, AdvancementProgress> progressById) {
        for (Map.Entry<ResourceLocation, AdvancementProgress> entry : progressById.entrySet()) {
            ResourceLocation advancementId = entry.getKey();
            AdvancementProgress progress = entry.getValue();
            if (advancementId != null && progress != null && progress.isDone()) {
                completedAdvancementIds.add(advancementId.toString());
            }
        }
    }

    private Optional<PresenceWorldEventPayload> payload(
            Minecraft minecraft,
            ClientboundUpdateAdvancementsPacket packet,
            ResourceLocation advancementId,
            AdvancementProgress progress
    ) {
        AdvancementHolder holder = advancementHolder(minecraft, packet, advancementId);
        if (holder == null) {
            return Optional.empty();
        }
        Optional<DisplayInfo> display = holder.value().display();
        if (display.isEmpty() || !playerFacing(display.get())) {
            return Optional.empty();
        }

        Map<String, String> values = new HashMap<>();
        values.put("advancementId", advancementId.toString());
        values.put("title", display.get().getTitle().getString());
        values.put("description", display.get().getDescription().getString());
        values.put("type", display.get().getType().getSerializedName());
        values.put("iconItemId", itemId(display.get().getIcon()));
        values.put("progressText", progressText(progress));
        values.put("source", "client_advancement_update");

        return Optional.of(new PresenceWorldEventPayload(
                "",
                PresenceWorldEventPayload.EVENT_ADVANCEMENT_UNLOCKED,
                minecraft.player == null ? "" : minecraft.player.getStringUUID(),
                minecraft.level == null ? "" : minecraft.level.dimension().location().toString(),
                System.currentTimeMillis(),
                values
        ));
    }

    private boolean playerFacing(DisplayInfo display) {
        return display.shouldShowToast() || display.shouldAnnounceChat();
    }

    private AdvancementHolder advancementHolder(Minecraft minecraft, ClientboundUpdateAdvancementsPacket packet, ResourceLocation advancementId) {
        try {
            ClientPacketListener connection = minecraft.getConnection();
            if (connection != null) {
                AdvancementHolder holder = connection.getAdvancements().get(advancementId);
                if (holder != null) {
                    return holder;
                }
            }
            for (AdvancementHolder holder : packet.getAdded()) {
                if (holder != null && holder.id().equals(advancementId)) {
                    return holder;
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Presence failed to resolve advancement {}: {}", advancementId, exception.getMessage());
        }
        return null;
    }

    private String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    private String progressText(AdvancementProgress progress) {
        try {
            return progress.getProgressText().getString();
        } catch (RuntimeException exception) {
            return "";
        }
    }
}
