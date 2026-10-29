package com.rheinmetal.tianshu.neoforge.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The one network channel Tianshu owns.
 *
 * <p>It is one-way by construction: the server component sends facts, the client receives them. There is no client to
 * server payload, so a client cannot feed itself authoritative facts, and this channel is not offered to other mods —
 * an external mod that needs server facts brings its own channel, exactly as the integration guide states.</p>
 */
public final class TianshuNetwork {

    /** Bumped when the fact payload changes shape; an older client simply refuses the channel. */
    private static final String PROTOCOL_VERSION = "1";

    private TianshuNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(GameFactPacket.TYPE, GameFactPacket.STREAM_CODEC, GameFactPacket::handle);
    }
}