package com.rheinmetal.tianshu.neoforge.network;

import com.rheinmetal.tianshu.mc.event.WorldEventHooks;

import com.rheinmetal.tianshu.mc.network.GameFactBatchPacket;

import com.rheinmetal.tianshu.client.tool.ToolCallClient;
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallResultPacket;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;
import com.rheinmetal.tianshu.protocol.ProtocolWireVersion;

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

    private TianshuNetwork() {
    }

    /** The protocol version currently on the wire; also reported in the session summary. */
    public static String protocolVersion() {
        return ProtocolWireVersion.VERSION;
    }

    public static void register(RegisterPayloadHandlersEvent event,
                                ToolCallClient tools) {
        // One registrar for every channel: a connection negotiates a single version, so splitting the catalogue onto
        // a newer one than the rest gave the server a channel it was allowed to name (hasChannel said yes) and a
        // payload it could never actually put on that connection. All Ferry channels are optional.
        PayloadRegistrar registrar = event.registrar(ProtocolWireVersion.VERSION).optional();
        // One enqueue keeps a batch atomic with respect to other client work: consumers see the facts in the
        // order the server produced them, not interleaved with another packet.
        registrar.playToClient(GameFactBatchPacket.TYPE, GameFactBatchPacket.STREAM_CODEC, (packet, context) -> {
            var events = packet.toEvents();
            if (events.isEmpty()) {
                return;
            }
            context.enqueueWork(() -> events.forEach(WorldEventHooks::publish));
        });
        registrar.playToClient(ToolCallResultPacket.TYPE, ToolCallResultPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> tools.receiveResult(packet.requestId(), packet.ok(), packet.valueJson(), packet.errorCode(), packet.message())));
        registrar.playToClient(ToolCatalogPacket.TYPE, ToolCatalogPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> tools.receiveCatalog(packet.tools())));
        registrar.playToServer(ToolCallRequestPacket.TYPE, ToolCallRequestPacket.STREAM_CODEC,
                com.rheinmetal.tianshu.neoforge.server.ServerToolHandler::handle);
    }
}
