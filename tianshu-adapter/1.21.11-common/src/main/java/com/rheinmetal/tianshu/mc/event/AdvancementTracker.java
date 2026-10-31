package com.rheinmetal.tianshu.mc.event;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import com.rheinmetal.tianshu.protocol.payload.WorldEventTypes;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns an advancement progress packet into the advancements the player actually unlocked.
 *
 * <p>The packet carries progress for a set of advancements and arrives in bulk — on login it carries everything
 * already done. The first packet after a reset is therefore a baseline, not news; only later transitions to "done"
 * are reported. A player-facing advancement is one with a toast or a chat announcement, which is exactly the set the
 * game itself considers worth telling the player about.</p>
 */
public final class AdvancementTracker {
    private final LogSink logSink;
    private final Set<String> completedAdvancementIds = new HashSet<>();
    private boolean baselineInitialized;

    AdvancementTracker() {
        this(LogSink.NOOP);
    }

    AdvancementTracker(LogSink logSink) {
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
    }

    void reset() {
        completedAdvancementIds.clear();
        baselineInitialized = false;
    }

    List<WorldEventPayload> collect(ClientboundUpdateAdvancementsPacket packet) {
        if (packet == null) {
            return List.of();
        }
        if (packet.shouldReset()) {
            reset();
        }

        Minecraft minecraft = Minecraft.getInstance();
        Map<Identifier, AdvancementProgress> progressById = packet.getProgress();
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

        List<WorldEventPayload> result = new ArrayList<>();
        for (Map.Entry<Identifier, AdvancementProgress> entry : progressById.entrySet()) {
            Identifier advancementId = entry.getKey();
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

    private void rememberCompleted(Map<Identifier, AdvancementProgress> progressById) {
        for (Map.Entry<Identifier, AdvancementProgress> entry : progressById.entrySet()) {
            Identifier advancementId = entry.getKey();
            AdvancementProgress progress = entry.getValue();
            if (advancementId != null && progress != null && progress.isDone()) {
                completedAdvancementIds.add(advancementId.toString());
            }
        }
    }

    private Optional<WorldEventPayload> payload(
            Minecraft minecraft,
            ClientboundUpdateAdvancementsPacket packet,
            Identifier advancementId,
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
        values.put(WorldEventTypes.VALUE_ADVANCEMENT_ID, advancementId.toString());
        values.put(WorldEventTypes.VALUE_TITLE, display.get().getTitle().getString());
        values.put(WorldEventTypes.VALUE_DESCRIPTION, display.get().getDescription().getString());
        values.put(WorldEventTypes.VALUE_TYPE, display.get().getType().getSerializedName());
        values.put(WorldEventTypes.VALUE_ICON_ITEM_ID, itemId(display.get().getIcon()));

        WorldEventPayload payload = new WorldEventPayload(
                WorldEventTypes.EVENT_ADVANCEMENT_UNLOCKED,
                minecraft.player == null ? "" : minecraft.player.getStringUUID(),
                minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString(),
                values,
                System.currentTimeMillis()
        );
        return Optional.of(payload);
    }

    private boolean playerFacing(DisplayInfo display) {
        return display.shouldShowToast() || display.shouldAnnounceChat();
    }

    private AdvancementHolder advancementHolder(Minecraft minecraft, ClientboundUpdateAdvancementsPacket packet, Identifier advancementId) {
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
            logSink.warn("neoforge.world_event.advancement_resolve_failed advancementId=" + advancementId
                    + " detail=" + exception.getMessage());
        }
        return null;
    }

    private String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }
}
