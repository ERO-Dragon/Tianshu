package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * The "who is acting" context a vanilla player-typed API needs, valid for one call.
 *
 * <p>A player acts as themselves. A body borrows a facade player positioned at the body; {@link #commit()} writes
 * changes back and {@link #close()} discards the facade. Two shapes exist: a full copy of the 36 main slots (for
 * placement and use, which may pick any slot), and a hand-only copy (for mining, where the game reads the main hand
 * and applies durability to it).</p>
 */
public final class ActionContext implements AutoCloseable {

    private static final int MAIN_SLOTS = 36;
    private static final int INVENTORY_SLOTS = 41;

    private final Player player;
    private final Container target;
    private final Container working;
    private final boolean facade;
    private final int handSlot;
    private final boolean handOnly;

    private ActionContext(Player player, Container target, Container working, boolean facade, int handSlot,
                          boolean handOnly) {
        this.player = player;
        this.target = target;
        this.working = working;
        this.facade = facade;
        this.handSlot = handSlot;
        this.handOnly = handOnly;
    }

    /** The player or facade to pass to vanilla APIs; never null for an opened context. */
    public Player player() {
        return player;
    }

    /** The inventory the call should read and write; for a body this is the facade's copy. */
    public Container inventory() {
        return working;
    }

    /** Writes the working slots back to the body. A player context has nothing to commit. */
    public void commit() {
        if (target == null) {
            return;
        }
        if (handOnly) {
            if (handSlot >= 0) {
                target.setItem(handSlot, working.getItem(0).copy());
            }
        } else {
            for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                target.setItem(slot, working.getItem(slot).copy());
            }
        }
        target.setChanged();
    }

    @Override
    public void close() {
        if (facade) {
            player.discard();
        }
    }

    public static Optional<ActionContext> open(MinecraftServer server, ActorRef actor, BodyRegistry bodies) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            ServerPlayer player = player(server, actor.id());
            if (player == null) {
                return Optional.empty();
            }
            return Optional.of(new ActionContext(player, null, player.getInventory(), false, -1, false));
        }
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null || body.inventory() == null) {
            return Optional.empty();
        }
        Player acting = body.identity().player(body);
        if (!body.identity().borrowed()) {
            // A player-backed body acts through its own player: its inventory *is* the body's, so nothing is copied.
            return Optional.of(new ActionContext(acting, null, acting.getInventory(), false, -1, false));
        }
        Inventory working = acting.getInventory();
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
            working.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
        }
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            working.setItem(slot, body.inventory().getItem(slot).copy());
        }
        return Optional.of(new ActionContext(acting, body.inventory(), working, true, -1, false));
    }

    /**
     * A context whose only carried item is the body's active slot, placed in the main hand. Mining reads the hand
     * and wears it down, so copying the whole inventory would let it mine with an item the body never equipped.
     */
    public static Optional<ActionContext> hand(MinecraftServer server, ActorRef actor, BodyRegistry bodies,
                                               int EntitySlot) {
        if (actor.kind() != ActorRef.Kind.BODY) {
            return Optional.empty();
        }
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null || body.inventory() == null) {
            return Optional.empty();
        }
        Player acting = body.identity().player(body);
        if (!body.identity().borrowed()) {
            // The body's own player mines with the slot the body has active — that is what a player would do.
            acting.getInventory().selected = Math.max(0, EntitySlot);
            return Optional.of(new ActionContext(acting, null, acting.getInventory(), false, EntitySlot, false));
        }
        Inventory working = acting.getInventory();
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
            working.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
        }
        if (EntitySlot >= 0) {
            working.setItem(0, body.inventory().getItem(EntitySlot).copy());
        }
        working.selected = 0;
        return Optional.of(new ActionContext(acting, body.inventory(), working, true, EntitySlot, true));
    }

    private static ServerPlayer player(MinecraftServer server, String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(playerId.trim()));
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}