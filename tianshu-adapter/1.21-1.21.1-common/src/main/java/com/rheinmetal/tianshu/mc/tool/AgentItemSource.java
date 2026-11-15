package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentItemPort;
import com.rheinmetal.tianshu.api.agent.EquipResult;
import com.rheinmetal.tianshu.api.agent.PickedItem;
import com.rheinmetal.tianshu.api.agent.PickupResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.mc.body.BodyRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 pickup: collected dropped stacks merge into the actor's 36 main slots.
 *
 * <p>It stops at the first stack that does not fit rather than shuffling items around, and reports exactly what it
 * took; partial collection is a normal result, an empty result means nothing was in reach.</p>
 */
public final class AgentItemSource implements AgentItemPort {

    private static final int MAX_RADIUS = 8;
    private static final int MAIN_SLOTS = 36;

    private final MinecraftServer server;
    private final BodyRegistry bodies;

    public AgentItemSource(MinecraftServer server, BodyRegistry bodies) {
        this.server = server;
        this.bodies = bodies;
    }

    @Override
    public CompletableFuture<EquipResult> equip(ActorRef actor, int slot) {
        CompletableFuture<EquipResult> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(equipNow(actor, slot));
            } catch (ToolException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private EquipResult equipNow(ActorRef actor, int slot) {
        if (actor.kind() != ActorRef.Kind.BODY) {
            throw new ToolException(ToolError.INVALID_ARGS, "equipping is a body concept");
        }
        BodyRegistry.BodyState state = bodies.state(actor.id()).orElseThrow(() -> new ToolException(
                ToolError.UNAVAILABLE, "the agent body is not present"));
        Container inventory = state.inventory();
        if (inventory == null) {
            throw new ToolException(ToolError.UNAVAILABLE, "that agent body has no inventory");
        }
        if (slot < -1 || slot >= MAIN_SLOTS) {
            throw new ToolException(ToolError.INVALID_ARGS, "slot must be -1 or 0..35");
        }
        if (!bodies.setActiveSlot(actor.id(), slot)) {
            throw new ToolException(ToolError.UNAVAILABLE, "the agent body is not present");
        }
        if (slot < 0) {
            return new EquipResult(-1, "", 0);
        }
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            return new EquipResult(slot, "", 0);
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new EquipResult(slot, id == null ? "" : id.toString(), stack.getCount());
    }

    @Override
    public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
        CompletableFuture<PickupResult> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(pickupNow(actor, radius));
            } catch (ToolException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private PickupResult pickupNow(ActorRef actor, int radius) {
        ToolActor holder = ToolActors.resolve(server, actor).orElseThrow(() -> new ToolException(
                ToolError.UNAVAILABLE, actor.kind() == ActorRef.Kind.BODY
                        ? "the agent body is not present"
                        : "the player is not online"));
        Container inventory = holder.inventory();
        if (inventory == null) {
            throw new ToolException(ToolError.UNAVAILABLE, "that agent body has no inventory");
        }
        int reach = Math.max(1, Math.min(radius, MAX_RADIUS));
        double reachSquared = (double) reach * reach;
        AABB area = AABB.ofSize(holder.position(), reach * 2.0, reach * 2.0, reach * 2.0);
        Map<String, Integer> collected = new LinkedHashMap<>();
        for (ItemEntity drop : holder.level().getEntitiesOfClass(ItemEntity.class, area,
                candidate -> !candidate.isRemoved())) {
            if (drop.position().distanceToSqr(holder.position()) > reachSquared) {
                continue;
            }
            ItemStack stack = drop.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            int moved = Inventories.add(inventory, stack, MAIN_SLOTS);
            if (moved <= 0) {
                break;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            collected.merge(id == null ? "" : id.toString(), moved, Integer::sum);
            stack.shrink(moved);
            if (stack.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(stack);
            }
        }
        List<PickedItem> items = new ArrayList<>();
        collected.forEach((itemId, count) -> items.add(new PickedItem(itemId, count)));
        return new PickupResult(List.copyOf(items));
    }
}