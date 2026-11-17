package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityItemPort;
import com.rheinmetal.tianshu.api.entity.EntitySlot;
import com.rheinmetal.tianshu.api.entity.DropResult;
import com.rheinmetal.tianshu.api.entity.EquipRequest;
import com.rheinmetal.tianshu.api.entity.EquipResult;
import com.rheinmetal.tianshu.api.entity.PickedItem;
import com.rheinmetal.tianshu.api.entity.PickupResult;
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
public final class AgentItemSource implements EntityItemPort {

    private static final int MAX_RADIUS = 8;
    private static final int MAIN_SLOTS = 36;
    /** How close a receiver has to be for a body to hand something over. */
    private static final double REACH = 4.0D;

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
        EntitySlot target = request.target();
        if (slot < -1 || slot >= MAIN_SLOTS) {
            throw new ToolException(ToolError.INVALID_ARGS, "slot must be -1 or 0..35");
        }
        if (target == EntitySlot.MAIN_HAND) {
            if (!bodies.setActiveSlot(actor.id(), slot)) {
                throw new ToolException(ToolError.UNAVAILABLE, "the agent body is not present");
            }
            if (slot < 0) {
                bodies.applyEquipment(state);
                return new EquipResult(EntitySlot.MAIN_HAND, -1, "", 0);
            }
            ItemStack stack = inventory.getItem(slot);
            bodies.applyEquipment(state);
            return new EquipResult(EntitySlot.MAIN_HAND, slot, itemId(stack), stack.getCount());
        }
        if (slot < 0) {
            ItemStack current = bodies.equipment(actor.id(), target);
            if (current.isEmpty()) {
                return new EquipResult(target, -1, "", 0);
            }
            // Ask for the room before moving anything. `add` places whatever fits, so checking its result
            // afterwards leaves the partial move in the inventory while the equipment slot still holds the full
            // stack: the items would be duplicated instead of the equip failing.
            if (Inventories.roomFor(inventory, current, MAIN_SLOTS) < current.getCount()) {
                throw new ToolException(ToolError.FAILED, "no room in the body inventory");
            }
            Inventories.add(inventory, current, MAIN_SLOTS);
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
    public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
        CompletableFuture<DropResult> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(dropNow(actor, slot, count));
            } catch (ToolException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private DropResult dropNow(ActorRef actor, int slot, int count) {
        BodyRegistry.BodyState state = bodies.state(actor.id()).orElseThrow(() -> new ToolException(
                ToolError.UNAVAILABLE, "the agent body is not present"));
        Container inventory = state.inventory();
        if (inventory == null) {
            throw new ToolException(ToolError.UNAVAILABLE, "that agent body has no inventory");
        }
        if (slot < 0 || slot >= MAIN_SLOTS) {
            throw new ToolException(ToolError.INVALID_ARGS, "slot must be 0..35");
        }
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            throw new ToolException(ToolError.INVALID_ARGS, "that inventory slot is empty");
        }
        int dropped = count <= 0 ? stack.getCount() : Math.min(count, stack.getCount());
        ItemStack out = stack.copyWithCount(dropped);
        stack.shrink(dropped);
        inventory.setChanged();
        spawnDrop(state.level(), state.position(), out);
        return new DropResult(itemId(stack), dropped);
    }

    @Override
    public CompletableFuture<DropResult> handTo(ActorRef actor, int slot, int count, String target) {
        CompletableFuture<DropResult> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(handToNow(actor, slot, count, target));
            } catch (ToolException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private DropResult handToNow(ActorRef actor, int slot, int count, String target) {
        BodyRegistry.BodyState state = bodies.state(actor.id()).orElseThrow(() -> new ToolException(
                ToolError.UNAVAILABLE, "the agent body is not present"));
        Container inventory = state.inventory();
        if (inventory == null) {
            throw new ToolException(ToolError.UNAVAILABLE, "that agent body has no inventory");
        }
        if (slot < 0 || slot >= MAIN_SLOTS) {
            throw new ToolException(ToolError.INVALID_ARGS, "slot must be 0..35");
        }
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            throw new ToolException(ToolError.INVALID_ARGS, "that inventory slot is empty");
        }
        net.minecraft.world.entity.Entity receiver = receiver(state, target);
        int dropped = count <= 0 ? stack.getCount() : Math.min(count, stack.getCount());
        ItemStack out = stack.copyWithCount(dropped);
        stack.shrink(dropped);
        inventory.setChanged();
        spawnDrop(state.level(), receiver.position(), out);
        return new DropResult(itemId(stack), dropped);
    }

    /** The player or entity a hand-over is aimed at, refused when absent or out of the body's reach. */
    private net.minecraft.world.entity.Entity receiver(BodyRegistry.BodyState state, String target) {
        String wanted = target == null ? "" : target.trim();
        if (wanted.isEmpty()) {
            throw new ToolException(ToolError.INVALID_ARGS, "handTo needs a player name or an entity id");
        }
        net.minecraft.world.entity.Entity found = server.getPlayerList().getPlayerByName(wanted);
        if (found == null) {
            try {
                found = state.level().getEntity(java.util.UUID.fromString(wanted));
            } catch (IllegalArgumentException notAUuid) {
                found = null;
            }
        }
        if (found == null) {
            throw new ToolException(ToolError.INVALID_ARGS, "no player or entity named " + wanted + " in this level");
        }
        if (state.position().distanceTo(found.position()) > REACH) {
            throw new ToolException(ToolError.INVALID_ARGS,
                    "that receiver is out of reach; a body hands things over at arm's length");
        }
        return found;
    }

    private static void spawnDrop(net.minecraft.server.level.ServerLevel level, net.minecraft.world.phys.Vec3 at,
                                  ItemStack stack) {
        level.addFreshEntity(new ItemEntity(level, at.x, at.y + 0.5D, at.z, stack));
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