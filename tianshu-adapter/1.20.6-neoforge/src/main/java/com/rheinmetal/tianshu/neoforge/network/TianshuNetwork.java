package com.rheinmetal.tianshu.neoforge.network;

import com.rheinmetal.tianshu.mc.event.WorldEventHooks;
import com.rheinmetal.tianshu.mc.network.GameFactBatchPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionCatalogPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionRequestPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionResultPacket;
import com.rheinmetal.tianshu.client.tool.ToolCallClient;
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallResultPacket;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;
import com.rheinmetal.tianshu.neoforge.TianshuNeoForge;
import com.rheinmetal.tianshu.neoforge.server.ServerActionHandler;
import com.rheinmetal.tianshu.protocol.ProtocolWireVersion;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The one network channel Tianshu owns.
 *
 * <p>The server component sends facts to a client, and a client sends whitelisted action requests to the server
 * component. There is no generic client payload, so a client cannot feed itself authoritative facts or invent an
 * action outside the handler's switch, and this channel is not offered to other mods.</p>
 *
 * <p>NeoForge 20.5 completes the payload registry: registration names a payload <em>type</em> together with its
 * {@link net.minecraft.network.codec.StreamCodec}, the direction is chosen by the registrar method itself, and the
 * handler is called with the decoded payload and an
 * {@link net.neoforged.neoforge.network.handling.IPayloadContext} on the main thread. A payload here is therefore
 * the shared record with its own codec; the loader only decides who may send it.</p>
 */
public final class TianshuNetwork {

    /** Bumped when a payload changes shape; an older peer simply refuses the channel. */
    private static final String PROTOCOL_VERSION = ProtocolWireVersion.VERSION;

    private TianshuNetwork() {
    }

    /** The protocol version currently on the wire; also reported in the session summary. */
    public static String protocolVersion() {
        return PROTOCOL_VERSION;
    }

    /**
     * Registers every payload, once per handshake.
     *
     * <p>The registration is optional on purpose: a player without Tianshu must not be disconnected just because the
     * server component happens to be installed, so an unknown payload is dropped instead of breaking the channel.</p>
     *
     * <p>The registrar delivers on the main thread, so the batch reaches its consumers in the order the server
     * produced it, never interleaved with another payload, and no further thread hop is needed.</p>
     */
    public static void register(RegisterPayloadHandlersEvent event, ToolCallClient tools) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToClient(GameFactBatchPacket.TYPE, GameFactBatchPacket.STREAM_CODEC,
                (packet, context) -> packet.toEvents().forEach(WorldEventHooks::publish));
        registrar.playToClient(ServerActionResultPacket.TYPE, ServerActionResultPacket.STREAM_CODEC,
                (packet, context) -> TianshuNeoForge.actionClient().receiveResult(packet, Runnable::run));
        registrar.playToClient(ServerActionCatalogPacket.TYPE, ServerActionCatalogPacket.STREAM_CODEC,
                (packet, context) -> TianshuNeoForge.actionClient().receiveCatalog(packet, Runnable::run));
        registrar.playToServer(ServerActionRequestPacket.TYPE, ServerActionRequestPacket.STREAM_CODEC,
                ServerActionHandler::handle);
        registrar.playToClient(ToolCallResultPacket.TYPE, ToolCallResultPacket.STREAM_CODEC,
                (packet, context) -> tools.receiveResult(packet.requestId(), packet.ok(), packet.valueJson(), packet.errorCode(), packet.message()));
        registrar.playToClient(ToolCatalogPacket.TYPE, ToolCatalogPacket.STREAM_CODEC,
                (packet, context) -> tools.receiveCatalog(packet.toolIds()));
        registrar.playToServer(ToolCallRequestPacket.TYPE, ToolCallRequestPacket.STREAM_CODEC,
                com.rheinmetal.tianshu.neoforge.server.ServerToolHandler::handle);
    }

    /** Sends one request to the server the client is connected to. */
    public static void sendToServer(CustomPacketPayload packet) {
        PacketDistributor.sendToServer(packet);
    }

    /** Sends one payload to a connected player; the caller checks {@link #canSend} first. */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * Whether a player could still receive a payload.
     *
     * <p>1.20.5 has no per-connection capability query on the listener, so what is still worth checking is the
     * connection itself: answering a player who has already left is the case this avoids. The channel is registered
     * as optional, so a client that does not know these payloads is never harmed by an attempt.</p>
     */
    public static boolean canSend(ServerPlayer player) {
        return player != null && player.connection != null;
    }
}
