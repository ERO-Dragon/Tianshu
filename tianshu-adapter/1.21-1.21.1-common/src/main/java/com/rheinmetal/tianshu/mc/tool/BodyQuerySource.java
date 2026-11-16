package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityQueryPort;
import com.rheinmetal.tianshu.api.entity.BlockHit;
import com.rheinmetal.tianshu.api.entity.EntityHit;
import com.rheinmetal.tianshu.api.entity.ScanRequest;
import com.rheinmetal.tianshu.api.entity.ScanResult;
import com.rheinmetal.tianshu.api.game.InventoryEntry;
import com.rheinmetal.tianshu.api.game.InventorySnapshot;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 perception: nearby blocks, nearby entities and the actor's own 36 main slots.
 *
 * <p>Reads only. The radius and the result count are clamped so a scan can never turn into a full-chunk walk, and
 * only loaded positions are read: an unloaded area is absent from the result rather than generated on demand.</p>
 */
public final class BodyQuerySource implements EntityQueryPort {

    private static final int MAX_RADIUS = 16;
    private static final int MAX_LIMIT = 256;
    private static final int MAIN_SLOTS = 36;

    private final MinecraftServer server;

    public BodyQuerySource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<ScanResult> scan(ActorRef actor, ScanRequest request) {
        CompletableFuture<ScanResult> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(scanNow(actor, request));
            } catch (ToolException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    @Override
    public CompletableFuture<InventorySnapshot> inventory(ActorRef actor) {
        CompletableFuture<InventorySnapshot> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(inventoryNow(actor));
            } catch (ToolException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private ScanResult scanNow(ActorRef actor, ScanRequest request) {
        ToolActor holder = ToolActors.resolve(server, actor).orElseThrow(() -> absent(actor));
        int radius = clamp(request.radius(), MAX_RADIUS);
        int limit = clamp(request.limit(), MAX_LIMIT);
        ServerLevel level = holder.level();
        BlockPos center = BlockPos.containing(holder.position());
        String dimension = level.dimension().location().toString();
        return new ScanResult(
                request.wantsBlocks() ? blocks(holder, level, center, dimension, radius, limit, request) : List.of(),
                request.wantsEntities() ? entities(actor, holder, level, dimension, radius, limit, request) : List.of());
    }

    private static List<BlockHit> blocks(ToolActor holder, ServerLevel level, BlockPos center, String dimension,
                                         int radius, int limit, ScanRequest request) {
        List<BlockHit> found = new ArrayList<>();
        double radiusSquared = (double) radius * radius;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (found.size() >= limit) {
                break;
            }
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            double dx = pos.getX() + 0.5 - holder.position().x;
            double dy = pos.getY() + 0.5 - holder.position().y;
            double dz = pos.getZ() + 0.5 - holder.position().z;
            if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            TagKey<Block> blockTag = blockTag(request.blockFilter());
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            boolean matches = blockTag != null
                    ? state.is(blockTag)
                    : id != null && id.toString().equals(request.blockFilter());
            if (!matches) {
                continue;
            }
            found.add(new BlockHit(id.toString(), dimension, pos.getX(), pos.getY(), pos.getZ()));
        }
        return List.copyOf(found);
    }

    private static List<EntityHit> entities(ActorRef actor, ToolActor holder, ServerLevel level, String dimension,
                                            int radius, int limit, ScanRequest request) {
        List<EntityHit> found = new ArrayList<>();
        double radiusSquared = (double) radius * radius;
        AABB area = AABB.ofSize(holder.position(), radius * 2.0, radius * 2.0, radius * 2.0);
        for (Entity entity : level.getEntities((Entity) null, area, candidate -> !candidate.isRemoved())) {
            if (found.size() >= limit) {
                break;
            }
            if (entity.getUUID().toString().equals(actor.id())) {
                continue;
            }
            if (entity.position().distanceToSqr(holder.position()) > radiusSquared) {
                continue;
            }
            ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            String filter = request.entityFilter();
            UUID wanted = uuid(filter);
            TagKey<EntityType<?>> typeTag = entityTag(filter);
            boolean matches;
            if (wanted != null) {
                matches = entity.getUUID().equals(wanted);
            } else if (typeTag != null) {
                matches = entity.getType().is(typeTag);
            } else {
                matches = type != null && type.toString().equals(filter);
            }
            if (!matches) {
                continue;
            }
            found.add(new EntityHit(entity.getUUID().toString(), type.toString(), dimension,
                    entity.getX(), entity.getY(), entity.getZ()));
        }
        return List.copyOf(found);
    }

    private InventorySnapshot inventoryNow(ActorRef actor) {
        ToolActor holder = ToolActors.resolve(server, actor).orElseThrow(() -> absent(actor));
        Container inventory = holder.inventory();
        if (inventory == null) {
            throw new ToolException(ToolError.UNAVAILABLE, "that agent body has no inventory");
        }
        List<InventoryEntry> slots = new ArrayList<>();
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            slots.add(new InventoryEntry(slot, id == null ? "" : id.toString(), stack.getCount()));
        }
        return new InventorySnapshot(List.copyOf(slots));
    }

    private static TagKey<Block> blockTag(String filter) {
        if (filter == null || !filter.startsWith("#")) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(filter.substring(1));
        return id == null ? null : TagKey.create(Registries.BLOCK, id);
    }

    private static TagKey<EntityType<?>> entityTag(String filter) {
        if (filter == null || !filter.startsWith("#")) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(filter.substring(1));
        return id == null ? null : TagKey.create(Registries.ENTITY_TYPE, id);
    }

    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static ToolException absent(ActorRef actor) {
        return new ToolException(ToolError.UNAVAILABLE, actor.kind() == ActorRef.Kind.BODY
                ? "the agent body is not present"
                : "the player is not online");
    }

    private static int clamp(int value, int max) {
        return Math.max(1, Math.min(value, max));
    }
}
