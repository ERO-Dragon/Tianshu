package com.rheinmetal.tianshu.mc.network;

/**
 * Sends one action request to the server.
 *
 * <p>How a client sends differs by loader (NeoForge's network channel, Fabric's {@code ClientPlayNetworking}), and
 * it is the only part of the action channel that does. Everything else — the request id, the pending futures, the
 * five-second bound, the catalog the server announced — is shared, so it is written once against this seam.</p>
 *
 * <p>1.20.1 has no common payload type to talk about, so the seam names the one request packet directly: it is the
 * only client-to-server message the product has, and pretending otherwise would be a type the era does not offer.</p>
 */
@FunctionalInterface
public interface PayloadSender {

    /**
     * Sends {@code payload} to the server.
     *
     * @throws RuntimeException when there is no connection or the channel refuses the payload; callers turn that
     *                          into a failed result rather than letting it escape into the game loop
     */
    void toServer(ServerActionRequestPacket payload);
}
