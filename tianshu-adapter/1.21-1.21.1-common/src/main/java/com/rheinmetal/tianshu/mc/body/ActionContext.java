package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.ActorRef;
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
 * <p>A player acts as themselves. A body borrows a facade player positioned at the body, with its 36 main slots
 * copied in; {@link #commit()} writes the slots back and {@link #close()} discards the facade. Nothing here is
 * stored between calls, which is what keeps the platform free of an "agent player" identity.</p>
 */
public final class ActionContext implements AutoCloseable {

    private static final int MAIN_SLOTS = 36;
    private static final int INVENTORY_SLOTS = 41;

    private final Player player;
    private final Container target;
    private final Container working;
    private final boolean facade;

    private ActionContext(Player player, Container target, Container working, boolean facade) {
        this.player = player;
        this.target = target;
        this.working = working;
        this.facade = facade;
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
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            target.setItem(slot, working.getItem(slot).copy());
        }
        target.setChanged();
    }

    @Override
    public void close() {
        if (facade) {
            player.discard();
        }
    }

    public static Optional<ActionContext> open(MinecraftServer server, ActorRef actor, BodyRegistry bodies,
                                               ActionPlayerFactory factory) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            ServerPlayer player = player(server, actor.id());
            if (player == null) {
                return Optional.empty();
            }
            return Optional.of(new ActionContext(player, null, player.getInventory(), false));
        }
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null || body.inventory() == null) {
            return Optional.empty();
        }
        Player facade = factory.create(body.level(), body.position(),
                body.entity().getYRot(), body.entity().getXRot());
        Inventory working = facade.getInventory();
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
            working.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
        }
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            working.setItem(slot, body.inventory().getItem(slot).copy());
        }
        return Optional.of(new ActionContext(facade, body.inventory(), working, true));
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