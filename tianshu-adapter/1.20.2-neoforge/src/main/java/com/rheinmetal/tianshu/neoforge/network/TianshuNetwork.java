package com.rheinmetal.tianshu.neoforge.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import com.rheinmetal.tianshu.mc.event.WorldEventHooks;
import com.rheinmetal.tianshu.mc.network.GameFactBatchPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionCatalogPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionRequestPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionResultPacket;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.NetworkEvent;
import net.neoforged.neoforge.network.NetworkRegistry;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.simple.SimpleChannel;

/**
 * The one network channel Tianshu owns.
 *
 * <p>The server component sends facts to a client, and a client sends whitelisted action requests to the server
 * component. There is no generic client payload, so a client cannot feed itself authoritative facts or invent an
 * action outside the handler's switch, and this channel is not offered to other mods.</p>
 *
 * <p>Forge 47 predates the payload registry: a channel is a {@link SimpleChannel} with numbered messages, each one a
 * plain record with a hand-written {@code FriendlyByteBuf} codec. Every message is registered on both sides so an
 * integrated server takes exactly the same path as a dedicated one.</p>
 */
public final class TianshuNetwork {

    /** Bumped when a message changes shape; an older peer simply refuses the channel. */
    private static final String PROTOCOL_VERSION = "3";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ModIdentity.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static boolean registered;

    private TianshuNetwork() {
    }

    /** The protocol version currently on the wire; also reported in the session summary. */
    public static String protocolVersion() {
        return PROTOCOL_VERSION;
    }

    /**
     * Registers every message once.
     *
     * <p>The client-to-server handler hands the decoded request to {@link
     * com.rheinmetal.tianshu.neoforge.server.ServerActionHandler#handle}, which runs the action on the server thread;
     * the server-to-client handlers hop through {@code enqueueWork} so the batch becomes visible at one exact moment,
     * in production order.</p>
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        CHANNEL.registerMessage(0, GameFactBatchPacket.class,
                GameFactBatchPacket::encode, GameFactBatchPacket::decode,
                (packet, context) -> {
                    
                    var events = packet.toEvents();
                    if (!events.isEmpty()) {
                        context.enqueueWork(() -> events.forEach(WorldEventHooks::publish));
                    }
                    context.setPacketHandled(true);
                });
        CHANNEL.registerMessage(1, ServerActionResultPacket.class,
                ServerActionResultPacket::encode, ServerActionResultPacket::decode,
                (packet, context) -> {
                    
                    com.rheinmetal.tianshu.neoforge.TianshuNeoForge.actionClient()
                            .receiveResult(packet, context::enqueueWork);
                    context.setPacketHandled(true);
                });
        CHANNEL.registerMessage(2, ServerActionCatalogPacket.class,
                ServerActionCatalogPacket::encode, ServerActionCatalogPacket::decode,
                (packet, context) -> {
                    
                    com.rheinmetal.tianshu.neoforge.TianshuNeoForge.actionClient()
                            .receiveCatalog(packet, context::enqueueWork);
                    context.setPacketHandled(true);
                });
        CHANNEL.registerMessage(3, ServerActionRequestPacket.class,
                ServerActionRequestPacket::encode, ServerActionRequestPacket::decode,
                (packet, context) -> com.rheinmetal.tianshu.neoforge.server.ServerActionHandler.handle(
                        packet, context));
    }

    /** Sends one message to the server the client is connected to. */
    public static void sendToServer(ServerActionRequestPacket packet) {
        CHANNEL.sendToServer(packet);
    }

    /** Sends one message to a connected player; the caller checks {@link #canSend} first. */
    public static void sendToPlayer(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * Whether a client has this channel open.
     *
     * <p>Asked instead of assumed: a vanilla client on a modded server is not spammed with messages it cannot read,
     * and a player whose connection is already gone is never answered.</p>
     */
    public static boolean canSend(ServerPlayer player) {
        return player != null && player.connection != null && CHANNEL.isRemotePresent(player.connection.connection);
    }
}
