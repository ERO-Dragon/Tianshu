package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.BlockInspectPort;
import com.rheinmetal.tianshu.api.game.BlockInspectRequest;
import com.rheinmetal.tianshu.api.game.BlockInspectResult;
import com.rheinmetal.tianshu.api.game.EnergyView;
import com.rheinmetal.tianshu.api.game.FluidTankView;
import com.rheinmetal.tianshu.api.game.InventoryEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 block read: identity, state, and every storage the block exposes.
 *
 * <p>Four reads in one answer because a model that has to make three round trips to learn what is in a machine will
 * guess instead. Items come from the vanilla container contract, fluids and energy from the loader through
 * {@link BlockStorageProbe}, and both are reported as "absent" rather than "empty" when the block has no such
 * storage.</p>
 *
 * <p>The reach rule is the same one the write tools use: an actor that could not touch the block cannot look inside it
 * either, so a body on the other side of the world learns nothing it should not.</p>
 */
public final class BlockInspectSource implements BlockInspectPort {

    private static final double MAX_DISTANCE_SQUARED = 36.0;

    private final MinecraftServer server;
    private final BlockStorageProbe probe;

    public BlockInspectSource(MinecraftServer server, BlockStorageProbe probe) {
        this.server = server;
        this.probe = probe == null ? BlockStorageProbe.NONE : probe;
    }

    @Override
    public CompletableFuture<BlockInspectResult> inspect(ActorRef actor, BlockInspectRequest request) {
        CompletableFuture<BlockInspectResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(inspectNow(actor, request)));
        return result;
    }

    private BlockInspectResult inspectNow(ActorRef actor, BlockInspectRequest request) {
        ToolActor holder = ToolActors.resolve(server, actor).orElse(null);
        if (holder == null) {
            return BlockInspectResult.failure(actor.kind() == ActorRef.Kind.BODY
                    ? "the agent body is not present"
                    : "the player is not online");
        }
        BlockPos pos = new BlockPos(request.x(), request.y(), request.z());
        if (!holder.level().isLoaded(pos)) {
            return BlockInspectResult.failure("that block is in a chunk that is not loaded");
        }
        if (holder.position().distanceToSqr(Vec3.atCenterOf(pos)) > MAX_DISTANCE_SQUARED) {
            return BlockInspectResult.failure("the block is out of reach");
        }
        BlockState state = holder.level().getBlockState(pos);
        if (state.isAir()) {
            return BlockInspectResult.failure("there is no block there");
        }
        String blockId = idOrEmpty(BuiltInRegistries.BLOCK.getKey(state.getBlock()));

        BlockEntity blockEntity = holder.level().getBlockEntity(pos);
        String blockEntityId = blockEntity == null
                ? "" : idOrEmpty(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()));

        List<InventoryEntry> items = itemsAt(holder.level(), pos);
        List<FluidTankView> fluids = probe.fluids(holder.level(), pos);
        EnergyView energy = probe.energy(holder.level(), pos);

        return new BlockInspectResult(blockId, blockEntityId, stateOf(state), items, fluids, energy, "inspected");
    }

    /**
     * Items through the shared store resolution, so the slot numbers reported here are the ones {@code container.transfer}
     * accepts. Reading the block entity directly covered vanilla containers only — which is how a modded machine with a
     * loader item handler ended up looking empty.
     */
    private List<InventoryEntry> itemsAt(ServerLevel level, BlockPos pos) {
        ItemStore store = ItemStores.of(level, pos, probe);
        if (store.size() <= 0) {
            return List.of();
        }
        List<InventoryEntry> entries = new ArrayList<>();
        for (int slot = 0; slot < store.size(); slot++) {
            ItemStack stack = store.stackIn(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            entries.add(new InventoryEntry(slot, key == null ? "" : key.toString(), stack.getCount()));
        }
        return entries;
    }

    /** Block state properties, sorted so the answer is stable between reads of the same block. */
    private static Map<String, String> stateOf(BlockState state) {
        Map<String, String> values = new TreeMap<>();
        for (Property<?> property : state.getProperties()) {
            values.put(property.getName(), valueOf(state, property));
        }
        return Collections.unmodifiableMap(values);
    }

    private static <T extends Comparable<T>> String valueOf(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static String idOrEmpty(ResourceLocation id) {
        return id == null ? "" : id.toString();
    }
}