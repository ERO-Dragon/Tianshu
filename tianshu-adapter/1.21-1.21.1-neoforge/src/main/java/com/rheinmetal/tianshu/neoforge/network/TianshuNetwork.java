package com.rheinmetal.tianshu.neoforge.network;

import com.rheinmetal.tianshu.mc.event.WorldEventHooks;
import com.rheinmetal.tianshu.mc.host.ServerActionClient;
import com.rheinmetal.tianshu.mc.network.GameFactBatchPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionCatalogPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionRequestPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionResultPacket;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The one network channel Tianshu owns.
 *
 * <p>Two channels: the server component sends facts to a client, and a client sends whitelisted action requests to
 * the server component. There is no generic client payload, so a client cannot feed itself authoritative facts or
 * invent an action outside the handler's switch, and this channel is not offered to other mods.</p>
 */
public final class TianshuNetwork {

    /** Bumped when a payload changes shape; an older client simply refuses the channel. */
    private static final String PROTOCOL_VERSION = "3";

    private TianshuNetwork() {
    }

    /** The protocol version currently on the wire; also reported in the session summary. */
    public static String protocolVersion() {
        return PROTOCOL_VERSION;
    }

    public static void register(RegisterPayloadHandlersEvent event, ServerActionClient actions) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        // One enqueue keeps a batch atomic with respect to other client work: consumers see the facts in the
        // order the server produced them, not interleaved with another packet.
        registrar.playToClient(GameFactBatchPacket.TYPE, GameFactBatchPacket.STREAM_CODEC, (packet, context) -> {
            var events = packet.toEvents();
            if (events.isEmpty()) {
                return;
            }
            context.enqueueWork(() -> events.forEach(WorldEventHooks::publish));
        });
        registrar.playToClient(ServerActionResultPacket.TYPE, ServerActionResultPacket.STREAM_CODEC,
                (packet, context) -> actions.receiveResult(packet, context::enqueueWork));
        registrar.playToClient(ServerActionCatalogPacket.TYPE, ServerActionCatalogPacket.STREAM_CODEC,
                (packet, context) -> actions.receiveCatalog(packet, context::enqueueWork));
        registrar.playToServer(ServerActionRequestPacket.TYPE, ServerActionRequestPacket.STREAM_CODEC,
                com.rheinmetal.tianshu.neoforge.server.ServerActionHandler::handle);
    }
}