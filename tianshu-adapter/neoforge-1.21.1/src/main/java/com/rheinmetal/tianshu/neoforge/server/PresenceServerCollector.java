package com.rheinmetal.tianshu.neoforge.server;

import com.rheinmetal.tianshu.neoforge.network.PresenceFactPacket;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
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

import java.util.HashMap;
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
public final class PresenceServerCollector {

    /** Subscribing is the whole registration; {@link #send} is the only network path. */
    public void register(IEventBus gameBus) {
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
    }

    public void onLivingDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getNewDamage() <= 0.0F) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(PresenceWorldEventPayload.VALUE_AMOUNT, Integer.toString(Math.round(event.getNewDamage())));
        send(player, PresenceWorldEventPayload.EVENT_DAMAGE_TAKEN, values);
    }

    public void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack picked = event.getCurrentStack();
        if (picked == null || picked.isEmpty()) {
            return;
        }
        send(player, PresenceWorldEventPayload.EVENT_ITEM_PICKUP, itemValues(picked));
    }

    public void onTrade(TradeWithVillagerEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getMerchantOffer() == null) {
            return;
        }
        ItemStack result = event.getMerchantOffer().getResult();
        if (result == null || result.isEmpty()) {
            return;
        }
        send(player, PresenceWorldEventPayload.EVENT_TRADE, itemValues(result));
    }

    public void onEffectExpired(MobEffectEvent.Expired event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        if (event.getEffectInstance() != null) {
            values.put(PresenceWorldEventPayload.VALUE_EFFECT_NAME,
                    event.getEffectInstance().getEffect().value().getDisplayName().getString());
        }
        send(player, PresenceWorldEventPayload.EVENT_EFFECT_EXPIRED, values);
    }

    public void onXpLevelChange(PlayerXpEvent.LevelChange event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(PresenceWorldEventPayload.VALUE_LEVEL, Integer.toString(player.experienceLevel));
        send(player, PresenceWorldEventPayload.EVENT_EXP_LEVEL, values);
    }

    /** A friend joining is visible in chat anyway; the fact goes to the other players, never to the joiner. */
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joining) || joining.getServer() == null) {
            return;
        }
        String name = joining.getGameProfile().getName();
        for (ServerPlayer other : joining.getServer().getPlayerList().getPlayers()) {
            if (other != joining) {
                send(other, PresenceWorldEventPayload.EVENT_FRIEND_JOIN, Map.of(
                        PresenceWorldEventPayload.VALUE_PLAYER_NAME, name));
            }
        }
    }

    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer leaving) || leaving.getServer() == null) {
            return;
        }
        String name = leaving.getGameProfile().getName();
        for (ServerPlayer other : leaving.getServer().getPlayerList().getPlayers()) {
            if (other != leaving) {
                send(other, PresenceWorldEventPayload.EVENT_FRIEND_LEAVE, Map.of(
                        PresenceWorldEventPayload.VALUE_PLAYER_NAME, name));
            }
        }
    }

    public void onEffectAdded(MobEffectEvent.Added event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getEffectInstance() == null) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(PresenceWorldEventPayload.VALUE_EFFECT_NAME,
                event.getEffectInstance().getEffect().value().getDisplayName().getString());
        send(player, PresenceWorldEventPayload.EVENT_EFFECT_GAINED, values);
    }

    /** Two facts, one event: the player's own death, and a kill the player made. */
    public void onLivingDeath(LivingDeathEvent event) {
        LivingEntity dying = event.getEntity();
        if (dying == null) {
            return;
        }
        if (dying instanceof ServerPlayer player) {
            Map<String, String> values = new HashMap<>();
            values.put(PresenceWorldEventPayload.VALUE_DEATH_MESSAGE,
                    player.getCombatTracker().getDeathMessage().getString());
            values.put(PresenceWorldEventPayload.VALUE_DIMENSION, dimensionId(player.level()));
            send(player, PresenceWorldEventPayload.EVENT_PLAYER_DEATH, values);
            return;
        }
        if (event.getSource() != null && event.getSource().getEntity() instanceof ServerPlayer killer) {
            Map<String, String> values = new HashMap<>();
            values.put(PresenceWorldEventPayload.VALUE_ENTITY_ID, entityId(dying));
            values.put(PresenceWorldEventPayload.VALUE_ENTITY_NAME, dying.getType().getDescription().getString());
            values.put(PresenceWorldEventPayload.VALUE_COUNT, "1");
            send(killer, PresenceWorldEventPayload.EVENT_MOB_KILLED, values);
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
        send(player, PresenceWorldEventPayload.EVENT_ITEM_CRAFTED, itemValues(crafted));
    }

    public void onItemFished(ItemFishedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        List<ItemStack> drops = event.getDrops();
        if (drops == null || drops.isEmpty()) {
            return;
        }
        send(player, PresenceWorldEventPayload.EVENT_FISHING_CATCH, itemValues(drops.get(0)));
    }

    public void onTamed(AnimalTameEvent event) {
        if (!(event.getTamer() instanceof ServerPlayer player) || event.getEntity() == null) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(PresenceWorldEventPayload.VALUE_ENTITY_ID, entityId(event.getEntity()));
        values.put(PresenceWorldEventPayload.VALUE_ENTITY_NAME,
                event.getEntity().getType().getDescription().getString());
        send(player, PresenceWorldEventPayload.EVENT_TAMED, values);
    }

    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        send(player, PresenceWorldEventPayload.EVENT_BLOCK_BROKEN, blockValues(event.getState()));
    }

    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        send(player, PresenceWorldEventPayload.EVENT_BLOCK_PLACED, blockValues(event.getPlacedBlock()));
    }

    public void onMount(EntityMountEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getEntityBeingMounted() == null) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(PresenceWorldEventPayload.VALUE_ENTITY_ID,
                Objects.requireNonNull(BuiltInRegistries.ENTITY_TYPE.getKey(
                        event.getEntityBeingMounted().getType())).toString());
        values.put(PresenceWorldEventPayload.VALUE_ENTITY_NAME,
                event.getEntityBeingMounted().getType().getDescription().getString());
        send(player, event.isMounting()
                ? PresenceWorldEventPayload.EVENT_MOUNT
                : PresenceWorldEventPayload.EVENT_DISMOUNT, values);
    }

    /** The one place that talks to the network; a client without the channel simply gets nothing. */
    private void send(ServerPlayer player, String factId, Map<String, String> values) {
        if (player == null || player.connection == null) {
            return;
        }
        if (!player.connection.hasChannel(PresenceFactPacket.TYPE)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new PresenceFactPacket(
                factId,
                dimensionId(player.level()),
                System.currentTimeMillis(),
                values));
    }

    private static Map<String, String> itemValues(ItemStack stack) {
        Map<String, String> values = new HashMap<>();
        values.put(PresenceWorldEventPayload.VALUE_ITEM_ID, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        // The name is resolved server-side, which is right in single player and may follow the server's language on a
        // dedicated one; the id travels next to it so a client can re-resolve it later.
        values.put(PresenceWorldEventPayload.VALUE_ITEM_NAME, stack.getHoverName().getString());
        values.put(PresenceWorldEventPayload.VALUE_COUNT, Integer.toString(stack.getCount()));
        return values;
    }

    private static Map<String, String> blockValues(BlockState state) {
        if (state == null) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        values.put(PresenceWorldEventPayload.VALUE_BLOCK_ID,
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        values.put(PresenceWorldEventPayload.VALUE_BLOCK_NAME, state.getBlock().getName().getString());
        return values;
    }

    private static String entityId(LivingEntity entity) {
        return Objects.requireNonNull(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())).toString();
    }

    private static String dimensionId(Level level) {
        return level == null ? "" : level.dimension().location().toString();
    }

    private static String dimensionName(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return "";
        }
        return Component.translatable(dimension.location().toLanguageKey("dimension")).getString();
    }
}