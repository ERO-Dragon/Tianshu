package com.rheinmetal.tianshu.mc.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Sends one payload to the server.
 *
 * <p>How a client sends differs by loader (NeoForge's distributor, Fabric's {@code ClientPlayNetworking}), and it
 * is the only part of the action channel that does. Everything else — the request id, the pending futures, the
 * five-second bound, the catalog the server announced — is shared, so it is written once against this seam.</p>
 */
@FunctionalInterface
public interface PayloadSender {

    /**
     * Sends {@code payload} to the server.
     *
     * @throws RuntimeException when there is no connection or the channel refuses the payload; callers turn that
     *                          into a failed result rather than letting it escape into the game loop
     */
    void toServer(CustomPacketPayload payload);
}
