package com.rheinmetal.tianshu.platform.provider;

import com.rheinmetal.tianshu.function.ia.context.DialogueContextFrame;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextProvider;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueEntityRef;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class NeoForgeDialogueContextProvider implements DialogueContextProvider {
    @Override
    public DialogueContextFrame capture(String playerId) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return DialogueContextFrame.empty(playerId);
        }

        LinkedHashSet<String> equippedItemIds = collectEquippedItemIds(player);
        String heldItemId = itemId(player.getMainHandItem());
        String dimensionId = minecraft.level.dimension().location().toString();
        DialogueEntityRef crosshairEntity = crosshairEntity(minecraft, player);
        List<DialogueEntityRef> entityRefs = crosshairEntity == null ? List.of() : List.of(crosshairEntity);
        boolean crosshairHit = crosshairEntity != null;

        DialogueInteractionHints hints = new DialogueInteractionHints(
                heldItemId,
                crosshairHit,
                minecraft.options.keyUse.isDown(),
                player.isShiftKeyDown(),
                crosshairEntity == null ? 0.0D : crosshairEntity.distance(),
                List.of()
        );
        DialogueContextSnapshot snapshot = new DialogueContextSnapshot(
                normalizePlayerId(playerId, player),
                dimensionId,
                entityRefs,
                List.copyOf(equippedItemIds),
                Map.of()
        );
        return new DialogueContextFrame(hints, snapshot);
    }

    private static LinkedHashSet<String> collectEquippedItemIds(Player player) {
        LinkedHashSet<String> itemIds = new LinkedHashSet<>();
        addItemId(player.getMainHandItem(), itemIds);
        addItemId(player.getOffhandItem(), itemIds);
        for (ItemStack stack : player.getArmorSlots()) {
            addItemId(stack, itemIds);
        }
        return itemIds;
    }

    private static void addItemId(ItemStack stack, LinkedHashSet<String> itemIds) {
        String id = itemId(stack);
        if (!id.isBlank()) {
            itemIds.add(id);
        }
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    private static DialogueEntityRef crosshairEntity(Minecraft minecraft, Player player) {
        HitResult hitResult = minecraft.hitResult;
        if (!(hitResult instanceof EntityHitResult entityHitResult)) {
            return null;
        }
        Entity entity = entityHitResult.getEntity();
        ResourceLocation typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String entityTypeId = typeKey == null ? "" : typeKey.toString();
        return new DialogueEntityRef(
                entity.getStringUUID(),
                entityTypeId,
                entity.getDisplayName().getString(),
                player.distanceTo(entity),
                true
        );
    }

    private static String normalizePlayerId(String requestedPlayerId, Player player) {
        if (requestedPlayerId != null && !requestedPlayerId.isBlank()) {
            return requestedPlayerId.trim();
        }
        return player.getStringUUID();
    }
}
