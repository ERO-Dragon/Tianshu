package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.mc.network.GameFact;
import com.rheinmetal.tianshu.mc.network.GameFactBatch;
import com.rheinmetal.tianshu.mc.network.GameFactBatchPacket;
import com.rheinmetal.tianshu.protocol.payload.WorldEventTypes;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The optional server component on Fabric: authoritative server events turned into facts for the acting player.
 *
 * <p>Nothing else runs on a dedicated server — no kernel, no modules, no assistant. Every listener answers one
 * question: "did something exact happen to this player?" and hands the fact to {@link #send}, the only place that
 * talks to the network. Facts are buffered for the rest of the tick and flushed once at its end, so a burst costs
 * one packet instead of one per fact.</p>
 *
 * <p>Scope discipline: a fact is only sent to the player it happened to. Facts about other players, containers or
 * inventories are not shipped. There is no "forward events?" switch: whether this component is installed
 * <em>is</em> the switch.</p>
 *
 * <p>Only kinds Fabric has a native server event for are produced here. Crafting, fishing, pickups, trades, taming,
 * effects, XP levels and mounts would each need a mixin or a poll; they are absent on this loader rather than
 * approximated, and {@code ServerFactCoverageTest} keeps the list honest.</p>
 */
public final class FabricGameFactCollector {

    /**
     * Facts produced during the current server tick, per receiving player.
     *
     * <p>Flushing happens once at the end of the tick; the list order is the production order, which is what a
     * receiver replays. All access is on the server thread.</p>
     */
    private final Map<ServerPlayer, List<GameFact>> pendingByPlayer = new LinkedHashMap<>();

    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> flush());
        ServerLivingEntityEvents.AFTER_DAMAGE.register(this::onDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(this::onDeath);
        PlayerBlockBreakEvents.AFTER.register(this::onBlockBroken);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onPlayerJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onPlayerLeave(handler.getPlayer()));
    }

    private void onDamage(LivingEntity entity, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
        if (!(entity instanceof ServerPlayer player) || damageTaken <= 0.0F) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(WorldEventTypes.VALUE_AMOUNT, Integer.toString(Math.round(damageTaken)));
        send(player, WorldEventTypes.EVENT_DAMAGE_TAKEN, values);
    }

    /** Two facts, one event: the player's own death, and a kill the player made. */
    private void onDeath(LivingEntity dying, DamageSource source) {
        if (dying instanceof ServerPlayer player) {
            Map<String, String> values = new HashMap<>();
            values.put(WorldEventTypes.VALUE_DEATH_MESSAGE, player.getCombatTracker().getDeathMessage().getString());
            values.put(WorldEventTypes.VALUE_DIMENSION, dimensionId(player.level()));
            send(player, WorldEventTypes.EVENT_PLAYER_DEATH, values);
            return;
        }
        if (source != null && source.getEntity() instanceof ServerPlayer killer) {
            Map<String, String> values = new HashMap<>();
            values.put(WorldEventTypes.VALUE_ENTITY_ID, entityId(dying));
            values.put(WorldEventTypes.VALUE_ENTITY_NAME, dying.getType().getDescription().getString());
            values.put(WorldEventTypes.VALUE_COUNT, "1");
            send(killer, WorldEventTypes.EVENT_MOB_KILLED, values);
        }
    }

    private void onBlockBroken(Level world, Player player, BlockPos pos, BlockState state, BlockEntity entity) {
        if (!(player instanceof ServerPlayer breaker) || state == null) {
            return;
        }
        send(breaker, WorldEventTypes.EVENT_BLOCK_BROKEN, blockValues(state));
    }

    /** A friend joining is visible in chat anyway; the fact goes to the other players, never to the joiner. */
    private void onPlayerJoin(ServerPlayer joining) {
        MinecraftServer server = joining == null ? null : joining.getServer();
        if (server == null) {
            return;
        }
        String name = joining.getGameProfile().getName();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != joining) {
                send(other, WorldEventTypes.EVENT_FRIEND_JOIN, Map.of(WorldEventTypes.VALUE_PLAYER_NAME, name));
            }
        }
    }

    private void onPlayerLeave(ServerPlayer leaving) {
        MinecraftServer server = leaving == null ? null : leaving.getServer();
        if (server == null) {
            return;
        }
        // The connection is already going away; buffered facts cannot be delivered and must not leak.
        pendingByPlayer.remove(leaving);
        String name = leaving.getGameProfile().getName();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != leaving) {
                send(other, WorldEventTypes.EVENT_FRIEND_LEAVE, Map.of(WorldEventTypes.VALUE_PLAYER_NAME, name));
            }
        }
    }

    /**
     * The one place that talks to the network.
     *
     * <p>A client that did not register the channel gets nothing: {@code canSend} is asked rather than assumed, so a
     * vanilla client on a modded server is not spammed with payloads it cannot read.</p>
     */
    private void send(ServerPlayer player, String factId, Map<String, String> values) {
        if (player == null || player.connection == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, GameFactBatchPacket.TYPE)) {
            return;
        }
        pendingByPlayer
                .computeIfAbsent(player, ignored -> new ArrayList<>())
                .add(new GameFact(factId, dimensionId(player.level()), System.currentTimeMillis(), values));
    }

    /** Sends everything buffered so far and clears the buffer; the batch size cap is why a tick can cost two packets. */
    private void flush() {
        if (pendingByPlayer.isEmpty()) {
            return;
        }
        pendingByPlayer.forEach((player, facts) -> {
            if (player.connection == null || !ServerPlayNetworking.canSend(player, GameFactBatchPacket.TYPE)) {
                return;
            }
            new GameFactBatch(facts)
                    .split(GameFactBatch.MAX_FACTS)
                    .forEach(batch -> ServerPlayNetworking.send(player, new GameFactBatchPacket(batch)));
        });
        pendingByPlayer.clear();
    }

    private static Map<String, String> blockValues(BlockState state) {
        Map<String, String> values = new HashMap<>();
        values.put(WorldEventTypes.VALUE_BLOCK_ID, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        values.put(WorldEventTypes.VALUE_BLOCK_NAME, state.getBlock().getName().getString());
        return values;
    }

    private static String entityId(LivingEntity entity) {
        return Objects.requireNonNull(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())).toString();
    }

    private static String dimensionId(Level level) {
        return level == null ? "" : level.dimension().location().toString();
    }
}
