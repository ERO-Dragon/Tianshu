package com.rheinmetal.tianshu.neoforge.network;

import com.rheinmetal.tianshu.mc.event.WorldEventHooks;
import com.rheinmetal.tianshu.mc.network.GameFactBatchPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionCatalogPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionRequestPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionResultPacket;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.registration.IPayloadRegistrar;

/**
 * The one network channel Tianshu owns.
 *
 * <p>The server component sends facts to a client, and a client sends whitelisted action requests to the server
 * component. There is no generic client payload, so a client cannot feed itself authoritative facts or invent an
 * action outside the handler's switch, and this channel is not offered to other mods.</p>
 *
 * <p>NeoForge 20.4 is the first generation with a payload registry, but it is the <em>early</em> shape of it: the
 * event is {@code RegisterPayloadHandlerEvent} (singular), the registrar reads a payload through a
 * {@link net.minecraft.network.FriendlyByteBuf} reader rather than a {@code StreamCodec}, and the direction is picked
 * through the handler builder. A payload here is therefore still the shared record with its hand-written codec; it
 * only declares its channel id and how to write itself.</p>
 */
public final class TianshuNetwork {

    /** Bumped when a payload changes shape; an older peer simply refuses the channel. */
    private static final String PROTOCOL_VERSION = "3";

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
     */
    public static void register(RegisterPayloadHandlerEvent event) {
        IPayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.play(GameFactBatchPacket.CHANNEL, GameFactBatchPacket::decode,
                builder -> builder.client((packet, context) -> {
                    var events = packet.toEvents();
                    if (events.isEmpty()) {
                        return;
                    }
                    // One hop keeps a batch atomic with respect to other client work: consumers see the facts in
                    // the order the server produced them, never interleaved with another payload.
                    context.workHandler().execute(() -> events.forEach(WorldEventHooks::publish));
                }));
        registrar.play(ServerActionResultPacket.CHANNEL, ServerActionResultPacket::decode,
                builder -> builder.client((packet, context) ->
                        com.rheinmetal.tianshu.neoforge.TianshuNeoForge.actionClient()
                                .receiveResult(packet, context.workHandler()::execute)));
        registrar.play(ServerActionCatalogPacket.CHANNEL, ServerActionCatalogPacket::decode,
                builder -> builder.client((packet, context) ->
                        com.rheinmetal.tianshu.neoforge.TianshuNeoForge.actionClient()
                                .receiveCatalog(packet, context.workHandler()::execute)));
        registrar.play(ServerActionRequestPacket.CHANNEL, ServerActionRequestPacket::decode,
                builder -> builder.server(com.rheinmetal.tianshu.neoforge.server.ServerActionHandler::handle));
    }

    /** Sends one request to the server the client is connected to. */
    public static void sendToServer(ServerActionRequestPacket packet) {
        PacketDistributor.SERVER.noArg().send(packet);
    }

    /** Sends one payload to a connected player; the caller checks {@link #canSend} first. */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.PLAYER.with(player).send(payload);
    }

    /**
     * Whether a player could still receive a payload.
     *
     * <p>1.20.4 has no per-connection capability query — that arrived later as {@code hasChannel(Type)} — and the
     * channel is registered as optional, so a client that does not know our payloads is never harmed by an attempt.
     * What is still worth checking is the connection itself: answering a player who has already left is the case this
     * avoids.</p>
     */
    public static boolean canSend(ServerPlayer player) {
        return player != null && player.connection != null;
    }
}
