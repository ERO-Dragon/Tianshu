package com.rheinmetal.tianshu.platform.provider;

import com.rheinmetal.tianshu.function.ia.context.DialogueContextFrame;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextProvider;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueEntityRef;
import com.rheinmetal.tianshu.function.ia.context.DialogueEntityInterest;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class NeoForgeDialogueContextProvider implements DialogueContextProvider {
    private final NearestWhitelistedEntityTracker nearestEntityTracker = new NearestWhitelistedEntityTracker();

    public void tick() {
        nearestEntityTracker.tick(Minecraft.getInstance());
    }

    @Override
    public void updateParticipants(List<DialogueParticipantDescriptor> participants) {
        nearestEntityTracker.updateInterest(DialogueEntityInterest.fromParticipants(participants));
    }

    @Override
    public DialogueContextFrame capture(String playerId) {
        return capture(playerId, List.of());
    }

    @Override
    public DialogueContextFrame capture(String playerId, List<DialogueParticipantDescriptor> participants) {
        nearestEntityTracker.updateInterest(DialogueEntityInterest.fromParticipants(participants));
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return DialogueContextFrame.empty(playerId);
        }

        LinkedHashSet<String> equippedItemIds = collectEquippedItemIds(player);
        String heldItemId = itemId(player.getMainHandItem());
        String dimensionId = minecraft.level.dimension().location().toString();
        DialogueEntityRef crosshairEntity = crosshairEntity(minecraft, player);
        List<DialogueEntityRef> entityRefs = entityRefs(crosshairEntity, nearestEntityTracker.snapshot());
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

    private static List<DialogueEntityRef> entityRefs(DialogueEntityRef crosshairEntity, DialogueEntityRef nearestEntity) {
        if (crosshairEntity == null && nearestEntity == null) {
            return List.of();
        }
        List<DialogueEntityRef> refs = new ArrayList<>(2);
        if (crosshairEntity != null) {
            refs.add(crosshairEntity);
        }
        if (nearestEntity != null && (crosshairEntity == null || !nearestEntity.entityId().equals(crosshairEntity.entityId()))) {
            refs.add(nearestEntity);
        }
        return List.copyOf(refs);
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

    private static final class NearestWhitelistedEntityTracker {
        private static final double HARD_MAX_RADIUS = 16.0D;
        private static final long MAX_CACHE_AGE_MILLIS = 1_000L;
        private static final int EMPTY_SCAN_INTERVAL_TICKS = 20;
        private static final int FAR_SCAN_INTERVAL_TICKS = 8;
        private static final int NEAR_SCAN_INTERVAL_TICKS = 4;

        private volatile CompiledEntityInterest interest = CompiledEntityInterest.empty();
        private volatile CachedEntity cachedEntity;
        private int ticksUntilScan;

        void updateInterest(DialogueEntityInterest newInterest) {
            CompiledEntityInterest normalized = CompiledEntityInterest.from(newInterest, HARD_MAX_RADIUS);
            if (interest.equals(normalized)) {
                return;
            }
            interest = normalized;
            cachedEntity = null;
            ticksUntilScan = 0;
        }

        void tick(Minecraft minecraft) {
            CompiledEntityInterest currentInterest = interest;
            if (!currentInterest.active()) {
                cachedEntity = null;
                return;
            }
            if (minecraft == null || minecraft.player == null || minecraft.level == null) {
                cachedEntity = null;
                return;
            }
            if (ticksUntilScan > 0) {
                ticksUntilScan--;
                return;
            }
            DialogueEntityRef nearest = scanNearest(minecraft, minecraft.player, currentInterest);
            long now = System.currentTimeMillis();
            cachedEntity = nearest == null ? null : new CachedEntity(nearest, now);
            ticksUntilScan = nextInterval(nearest, currentInterest);
        }

        DialogueEntityRef snapshot() {
            CachedEntity cached = cachedEntity;
            if (cached == null || System.currentTimeMillis() - cached.capturedAtMillis() > MAX_CACHE_AGE_MILLIS) {
                return null;
            }
            return cached.ref();
        }

        private DialogueEntityRef scanNearest(Minecraft minecraft, Player player, CompiledEntityInterest currentInterest) {
            double radius = currentInterest.maxDistance();
            if (radius <= 0.0D) {
                return null;
            }
            List<Entity> entities = minecraft.level.getEntities(player, player.getBoundingBox().inflate(radius), entity -> matchesInterest(entity, currentInterest));
            Entity nearest = null;
            double nearestDistanceSqr = Double.MAX_VALUE;
            for (Entity entity : entities) {
                double distanceSqr = player.distanceToSqr(entity);
                if (distanceSqr < nearestDistanceSqr) {
                    nearest = entity;
                    nearestDistanceSqr = distanceSqr;
                }
            }
            return nearest == null ? null : entityRef(nearest, player);
        }

        private boolean matchesInterest(Entity entity, CompiledEntityInterest currentInterest) {
            if (entity == null || !entity.isAlive()) {
                return false;
            }
            ResourceLocation typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            String entityTypeId = typeKey == null ? "" : typeKey.toString();
            return currentInterest.matches(entityTypeId);
        }

        private static DialogueEntityRef entityRef(Entity entity, Player player) {
            ResourceLocation typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return new DialogueEntityRef(
                    entity.getStringUUID(),
                    typeKey == null ? "" : typeKey.toString(),
                    entity.getDisplayName().getString(),
                    player.distanceTo(entity),
                    false
            );
        }

        private static int nextInterval(DialogueEntityRef nearest, CompiledEntityInterest currentInterest) {
            if (nearest == null) {
                return EMPTY_SCAN_INTERVAL_TICKS;
            }
            return nearest.distance() <= currentInterest.maxDistance() * 0.5D ? NEAR_SCAN_INTERVAL_TICKS : FAR_SCAN_INTERVAL_TICKS;
        }

        private record CachedEntity(DialogueEntityRef ref, long capturedAtMillis) {
        }

        private record CompiledEntityInterest(Set<String> exactTypeIds, List<String> namespacePrefixes, double maxDistance) {
            private static CompiledEntityInterest empty() {
                return new CompiledEntityInterest(Set.of(), List.of(), 0.0D);
            }

            private static CompiledEntityInterest from(DialogueEntityInterest source, double hardMaxRadius) {
                if (source == null || !source.active()) {
                    return empty();
                }
                Set<String> exact = new HashSet<>();
                List<String> prefixes = new ArrayList<>();
                for (String entityTypeId : source.entityTypeIds()) {
                    if (entityTypeId == null || entityTypeId.isBlank()) {
                        continue;
                    }
                    String normalized = entityTypeId.trim().toLowerCase(Locale.ROOT);
                    if (normalized.endsWith(":*")) {
                        prefixes.add(normalized.substring(0, normalized.length() - 1));
                    } else {
                        exact.add(normalized);
                    }
                }
                double radius = Math.min(source.maxDistance(), hardMaxRadius);
                if ((exact.isEmpty() && prefixes.isEmpty()) || radius <= 0.0D) {
                    return empty();
                }
                return new CompiledEntityInterest(Set.copyOf(exact), List.copyOf(prefixes), radius);
            }

            private boolean active() {
                return maxDistance > 0.0D && (!exactTypeIds.isEmpty() || !namespacePrefixes.isEmpty());
            }

            private boolean matches(String entityTypeId) {
                if (entityTypeId == null || entityTypeId.isBlank()) {
                    return false;
                }
                String normalized = entityTypeId.trim().toLowerCase(Locale.ROOT);
                if (exactTypeIds.contains(normalized)) {
                    return true;
                }
                for (String prefix : namespacePrefixes) {
                    if (normalized.startsWith(prefix)) {
                        return true;
                    }
                }
                return false;
            }
        }
    }
}
