package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentItemPort;
import com.rheinmetal.tianshu.api.agent.BodySlot;
import com.rheinmetal.tianshu.api.agent.EquipRequest;
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
    public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
        CompletableFuture<EquipResult> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(equipNow(actor, request));
            } catch (ToolException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private EquipResult equipNow(ActorRef actor, EquipRequest request) {
        if (actor.kind() != ActorRef.Kind.BODY) {
            throw new ToolException(ToolError.INVALID_ARGS, "equipping is a body concept");
        }
        BodyRegistry.BodyState state = bodies.state(actor.id()).orElseThrow(() -> new ToolException(
                ToolError.UNAVAILABLE, "the agent body is not present"));
        Container inventory = state.inventory();
        if (inventory == null) {
            throw new ToolException(ToolError.UNAVAILABLE, "that agent body has no inventory");
        }
        int slot = request.inventorySlot();
        BodySlot target = request.target();
        if (slot < -1 || slot >= MAIN_SLOTS) {
            throw new ToolException(ToolError.INVALID_ARGS, "slot must be -1 or 0..35");
        }
        if (target == BodySlot.MAIN_HAND) {
            if (!bodies.setActiveSlot(actor.id(), slot)) {
                throw new ToolException(ToolError.UNAVAILABLE, "the agent body is not present");
            }
            if (slot < 0) {
                bodies.applyEquipment(state);
                return new EquipResult(BodySlot.MAIN_HAND, -1, "", 0);
            }
            ItemStack stack = inventory.getItem(slot);
            bodies.applyEquipment(state);
            return new EquipResult(BodySlot.MAIN_HAND, slot, itemId(stack), stack.getCount());
        }
        if (slot < 0) {
            ItemStack current = bodies.equipment(actor.id(), target);
            if (current.isEmpty()) {
                return new EquipResult(target, -1, "", 0);
            }
            int moved = Inventories.add(inventory, current, MAIN_SLOTS);
            if (moved < current.getCount()) {
                throw new ToolException(ToolError.FAILED, "no room in the body inventory");
            }
            bodies.setEquipment(actor.id(), target, ItemStack.EMPTY);
            bodies.applyEquipment(state);
            return new EquipResult(target, -1, "", 0);
        }
        ItemStack from = inventory.getItem(slot);
        if (from.isEmpty()) {
            throw new ToolException(ToolError.INVALID_ARGS, "that inventory slot is empty");
        }
        ItemStack previous = bodies.equipment(actor.id(), target);
        bodies.setEquipment(actor.id(), target, from);
        inventory.setItem(slot, previous.isEmpty() ? ItemStack.EMPTY : previous.copy());
        inventory.setChanged();
        bodies.applyEquipment(state);
        return new EquipResult(target, slot, itemId(from), from.getCount());
    }

    private static String itemId(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return stack.isEmpty() || id == null ? "" : id.toString();
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