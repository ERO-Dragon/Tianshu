package com.rheinmetal.tianshu.neoforge.network;

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

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(GameFactBatchPacket.TYPE, GameFactBatchPacket.STREAM_CODEC, GameFactBatchPacket::handle);
        registrar.playToClient(ServerActionResultPacket.TYPE, ServerActionResultPacket.STREAM_CODEC,
                com.rheinmetal.tianshu.neoforge.adapter.NeoForgeServerActionClient::handleResult);
        registrar.playToClient(ServerActionCatalogPacket.TYPE, ServerActionCatalogPacket.STREAM_CODEC,
                com.rheinmetal.tianshu.neoforge.adapter.NeoForgeServerActionClient::handleCatalog);
        registrar.playToServer(ServerActionRequestPacket.TYPE, ServerActionRequestPacket.STREAM_CODEC,
                com.rheinmetal.tianshu.neoforge.server.ServerActionHandler::handle);
    }
}