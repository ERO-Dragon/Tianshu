package com.rheinmetal.tianshu.neoforge.server;

import com.rheinmetal.tianshu.mc.network.GameFact;
import com.rheinmetal.tianshu.mc.network.GameFactBatch;
import com.rheinmetal.tianshu.mc.network.GameFactBatchPacket;
import com.rheinmetal.tianshu.protocol.payload.WorldEventTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The optional server component: it turns authoritative server events into facts for the acting player.
 *
 * <p>Nothing else runs on a dedicated server — no kernel, no modules, no assistant. Every listener answers one
 * question: "did something exact happen to this player?" and hands the fact to {@link #send}, the only place that
 * talks to the network.</p>
 *
 * <p>Scope discipline: a fact is only sent to the player it happened to. Facts about other players, containers or
 * inventories are not shipped, because the client has no use for them and the server has no business broadcasting
 * them. There is no "forward events?" switch: whether this component is installed <em>is</em> the switch.</p>
 *
 * <p>Every fact below is one a client cannot observe on its own. Facts the client already sees (advancement packets,
 * chat) are not duplicated here.</p>
 */
public final class GameFactCollector {

    /**
     * Facts produced during the current server tick, per receiving player.
     *
     * <p>Flushing happens once at the end of the tick, so a burst costs one packet instead of one per fact. The
     * list order is the production order; the receiver replays it as-is. All access is on the server thread.</p>
     */
    private final Map<ServerPlayer, List<GameFact>> pendingByPlayer = new LinkedHashMap<>();
    private final Map<ServerPlayer, Boolean> sleepingOld = new java.util.HashMap<>();
    private final Map<ServerPlayer, Boolean> eatingOld = new java.util.HashMap<>();
    private final Map<ServerLevel, String> weatherOld = new java.util.HashMap<>();

    /** Subscribing is the whole registration; {@link #send} is the only network path. */
    public void register(IEventBus gameBus) {
        gameBus.addListener(this::onServerTick);
        gameBus.addListener(this::onLivingDeath);
        gameBus.addListener(this::onItemCrafted);
        gameBus.addListener(this::onItemFished);
        gameBus.addListener(this::onTamed);
        gameBus.addListener(this::onBlockBreak);
        gameBus.addListener(this::onBlockPlace);
        gameBus.addListener(this::onMount);
        gameBus.addListener(this::onEffectAdded);
        gameBus.addListener(this::onEffectExpired);
        gameBus.addListener(this::onXpLevelChange);
        gameBus.addListener(this::onPlayerJoin);
        gameBus.addListener(this::onPlayerLeave);
        gameBus.addListener(this::onLivingDamage);
        gameBus.addListener(this::onItemPickup);
        gameBus.addListener(this::onTrade);
        gameBus.addListener(this::onRespawn);
    }

    public void onLivingDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getNewDamage() <= 0.0F) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(WorldEventTypes.VALUE_AMOUNT, Integer.toString(Math.round(event.getNewDamage())));
        // Falling has its own line: the amount alone reads the same as any other hit, and "I fell" is the fact the
        // player actually wants commented on.
        send(player, isFall(event.getSource())
                ? WorldEventTypes.EVENT_FALL_DAMAGE
                : WorldEventTypes.EVENT_DAMAGE_TAKEN, values);
    }

    /** Whether the damage came from falling; the message id is the stable way to ask without a registry lookup. */
    private static boolean isFall(net.minecraft.world.damagesource.DamageSource source) {
        return source != null && "fall".equals(source.getMsgId());
    }

    public void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        // The event reports the stack *left on the ground*, not the one that was picked up. A whole stack leaves
        // nothing behind, which is the common case, so the amount has to be the difference; using the remainder
        // directly would report every full pickup as "nothing happened".
        ItemStack original = event.getOriginalStack();
        ItemStack remaining = event.getCurrentStack();
        int originalCount = original == null ? 0 : original.getCount();
        int remainingCount = remaining == null ? 0 : remaining.getCount();
        int pickedCount = originalCount - remainingCount;
        if (original == null || original.isEmpty() || pickedCount <= 0) {
            return;
        }
        Map<String, String> values = itemValues(original);
        values.put(WorldEventTypes.VALUE_COUNT, Integer.toString(pickedCount));
        // A rare find is its own event: it is worth a sentence even when the player picks up ten of something else,
        // and reporting it as a plain pickup would put it through the "frequent, weakened" policy row.
        send(player, rare(original) ? WorldEventTypes.EVENT_RARE_ITEM : WorldEventTypes.EVENT_ITEM_PICKUP, values);
    }

    /** The game's own rarity, rare and above: no curated list to keep in sync with mods. */
    private static boolean rare(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.world.item.Rarity rarity = stack.getRarity();
        return rarity == net.minecraft.world.item.Rarity.RARE || rarity == net.minecraft.world.item.Rarity.EPIC;
    }

    public void onTrade(TradeWithVillagerEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getMerchantOffer() == null) {
            return;
        }
        ItemStack result = event.getMerchantOffer().getResult();
        if (result == null || result.isEmpty()) {
            return;
        }
        send(player, WorldEventTypes.EVENT_TRADE, itemValues(result));
    }

    public void onEffectExpired(MobEffectEvent.Expired event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        if (event.getEffectInstance() != null) {
            values.put(WorldEventTypes.VALUE_EFFECT_NAME,
                    event.getEffectInstance().getEffect().value().getDisplayName().getString());
        }
        send(player, WorldEventTypes.EVENT_EFFECT_EXPIRED, values);
    }

    public void onXpLevelChange(PlayerXpEvent.LevelChange event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(WorldEventTypes.VALUE_LEVEL, Integer.toString(player.experienceLevel));
        send(player, WorldEventTypes.EVENT_EXP_LEVEL, values);
    }

    /** A friend joining is visible in chat anyway; the fact goes to the other players, never to the joiner. */
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joining) || joining.getServer() == null) {
            return;
        }
        String name = joining.getGameProfile().getName();
        for (ServerPlayer other : joining.getServer().getPlayerList().getPlayers()) {
            if (other != joining) {
                send(other, WorldEventTypes.EVENT_FRIEND_JOIN, Map.of(
                        WorldEventTypes.VALUE_PLAYER_NAME, name));
            }
        }
    }

    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer leaving) || leaving.getServer() == null) {
            return;
        }
        // The connection is already going away; buffered facts cannot be delivered and must not leak.
        pendingByPlayer.remove(leaving);
        String name = leaving.getGameProfile().getName();
        for (ServerPlayer other : leaving.getServer().getPlayerList().getPlayers()) {
            if (other != leaving) {
                send(other, WorldEventTypes.EVENT_FRIEND_LEAVE, Map.of(
                        WorldEventTypes.VALUE_PLAYER_NAME, name));
            }
        }
    }

    public void onEffectAdded(MobEffectEvent.Added event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getEffectInstance() == null) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(WorldEventTypes.VALUE_EFFECT_NAME,
                event.getEffectInstance().getEffect().value().getDisplayName().getString());
        send(player, WorldEventTypes.EVENT_EFFECT_GAINED, values);
    }

    /** Two facts, one event: the player's own death, and a kill the player made. */
    public void onLivingDeath(LivingDeathEvent event) {
        LivingEntity dying = event.getEntity();
        if (dying == null) {
            return;
        }
        if (dying instanceof ServerPlayer player) {
            Map<String, String> values = new HashMap<>();
            values.put(WorldEventTypes.VALUE_DEATH_MESSAGE,
                    player.getCombatTracker().getDeathMessage().getString());
            values.put(WorldEventTypes.VALUE_DIMENSION, dimensionId(player.level()));
            send(player, WorldEventTypes.EVENT_PLAYER_DEATH, values);
            return;
        }
        if (event.getSource() != null && event.getSource().getEntity() instanceof ServerPlayer killer) {
            Map<String, String> values = new HashMap<>();
            values.put(WorldEventTypes.VALUE_ENTITY_ID, entityId(dying));
            values.put(WorldEventTypes.VALUE_ENTITY_NAME, dying.getType().getDescription().getString());
            values.put(WorldEventTypes.VALUE_COUNT, "1");
            send(killer, WorldEventTypes.EVENT_MOB_KILLED, values);
        }
    }

    public void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack crafted = event.getCrafting();
        if (crafted == null || crafted.isEmpty()) {
            return;
        }
        send(player, WorldEventTypes.EVENT_ITEM_CRAFTED, itemValues(crafted));
    }

    public void onItemFished(ItemFishedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        List<ItemStack> drops = event.getDrops();
        if (drops == null || drops.isEmpty()) {
            return;
        }
        send(player, WorldEventTypes.EVENT_FISHING_CATCH, itemValues(drops.get(0)));
    }

    public void onTamed(AnimalTameEvent event) {
        if (!(event.getTamer() instanceof ServerPlayer player) || event.getEntity() == null) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(WorldEventTypes.VALUE_ENTITY_ID, entityId(event.getEntity()));
        values.put(WorldEventTypes.VALUE_ENTITY_NAME,
                event.getEntity().getType().getDescription().getString());
        send(player, WorldEventTypes.EVENT_TAMED, values);
    }

    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        send(player, WorldEventTypes.EVENT_BLOCK_BROKEN, blockValues(event.getState()));
    }

    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        send(player, WorldEventTypes.EVENT_BLOCK_PLACED, blockValues(event.getPlacedBlock()));
    }

    public void onMount(EntityMountEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getEntityBeingMounted() == null) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(WorldEventTypes.VALUE_ENTITY_ID,
                Objects.requireNonNull(BuiltInRegistries.ENTITY_TYPE.getKey(
                        event.getEntityBeingMounted().getType())).toString());
        values.put(WorldEventTypes.VALUE_ENTITY_NAME,
                event.getEntityBeingMounted().getType().getDescription().getString());
        send(player, event.isMounting()
                ? WorldEventTypes.EVENT_MOUNT
                : WorldEventTypes.EVENT_DISMOUNT, values);
    }

    /** The one place that talks to the network; a client without the channel simply gets nothing. */
    private void send(ServerPlayer player, String factId, Map<String, String> values) {
        if (player == null || player.connection == null) {
            return;
        }
        if (!player.connection.hasChannel(GameFactBatchPacket.TYPE)) {
            return;
        }
        pendingByPlayer
                .computeIfAbsent(player, ignored -> new ArrayList<>())
                .add(new GameFact(factId, dimensionId(player.level()), System.currentTimeMillis(), values));
    }

    /** End of the server tick: every buffered fact goes out in order, split into bounded packets. */
    public void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        // Three facts have no event worth hooking on both loaders, so they are read as state transitions here:
        // sleeping, eating and the weather. Reading a transition is the same cost as reading a flag.
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            sampleSleeping(player);
            sampleEating(player);
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            sampleWeather(level);
        }
        flush();
    }

    /** Sleeping on and off, per player: `sleep` is the moment they lay down. */
    private void sampleSleeping(ServerPlayer player) {
        boolean sleeping = player.isSleeping();
        Boolean was = sleepingOld.put(player, sleeping);
        if (was == null || was == sleeping) {
            return;
        }
        if (sleeping) {
            send(player, WorldEventTypes.EVENT_SLEEP, Map.of());
        }
    }

    /** Eating has no completion event in common across loaders; the end of item use plus an edible item is the fact. */
    private void sampleEating(ServerPlayer player) {
        boolean using = player.isUsingItem() && player.getUseItem().get(net.minecraft.core.component.DataComponents.FOOD) != null;
        Boolean was = eatingOld.put(player, using);
        if (was == null || was == using) {
            return;
        }
        if (!using && was) {
            send(player, WorldEventTypes.EVENT_EAT_FOOD, itemValues(player.getUseItem().copy()));
        }
    }

    /** Weather is world state: the transition is what the line reports, and the token names the new state. */
    private void sampleWeather(ServerLevel level) {
        String state = level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear";
        String before = weatherOld.put(level, state);
        if (before == null || before.equals(state)) {
            return;
        }
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level) {
                send(player, WorldEventTypes.EVENT_WEATHER_CHANGE, Map.of(WorldEventTypes.VALUE_TYPE, state));
            }
        }
    }

    /** Coming back is its own moment: death is the fact, respawn is when play continues. */
    public void onRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        send(player, WorldEventTypes.EVENT_RESPAWN,
                Map.of(WorldEventTypes.VALUE_DIMENSION, dimensionId(player.level())));
    }

    /**
     * Sends everything buffered so far and clears the buffer; the batch size cap is the only reason a tick's facts
     * become more than one packet.
     */
    public void flush() {
        if (pendingByPlayer.isEmpty()) {
            return;
        }
        pendingByPlayer.forEach((player, facts) -> {
            if (player.connection == null || !player.connection.hasChannel(GameFactBatchPacket.TYPE)) {
                return;
            }
            new GameFactBatch(facts)
                    .split(GameFactBatch.MAX_FACTS)
                    .forEach(batch -> PacketDistributor.sendToPlayer(player, new GameFactBatchPacket(batch)));
        });
        pendingByPlayer.clear();
    }

    private static Map<String, String> itemValues(ItemStack stack) {
        Map<String, String> values = new HashMap<>();
        values.put(WorldEventTypes.VALUE_ITEM_ID, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        // The name is resolved server-side, which is right in single player and may follow the server's language on a
        // dedicated one; the id travels next to it so a client can re-resolve it later.
        values.put(WorldEventTypes.VALUE_ITEM_NAME, stack.getHoverName().getString());
        values.put(WorldEventTypes.VALUE_COUNT, Integer.toString(stack.getCount()));
        return values;
    }

    private static Map<String, String> blockValues(BlockState state) {
        if (state == null) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        values.put(WorldEventTypes.VALUE_BLOCK_ID,
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
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