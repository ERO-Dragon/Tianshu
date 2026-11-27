package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who a tool request from a client is, as far as the server is concerned.
 *
 * <p>The packet carries one fact about the caller — the module id that asked, empty when the player asked directly.
 * The other half of the identity is never taken from the packet: it is the player the connection belongs to. That is
 * what makes "this agent is acting for player X" unforgeable, and it is why both loaders derive the caller here
 * instead of each writing its own rule.</p>
 *
 * <p>A proxied call is judged at {@link ToolAutonomy#CONSERVATIVE}: the caller's own tier is how far it decided to go
 * on its own, and the server must not accept a widened claim over the wire. A caller can still be refused by the
 * server's ceiling, a tool's switch or its permission — those checks are unchanged.</p>
 */
public final class ToolRequestCaller {

    /** The tier a call gets when an agent, not the player, is asking. */
    public static final ToolAutonomy PROXY_AUTONOMY = ToolAutonomy.CONSERVATIVE;

    private ToolRequestCaller() {
    }

    /** The caller of this request: the player on the connection, or the module asking for it. */
    public static ToolCaller of(ToolCallRequestPacket packet, ServerPlayer player, int operatorPermissionLevel) {
        String module = packet.callerModule();
        String playerId = player.getUUID().toString();
        if (module == null || module.isBlank()) {
            return ToolCaller.player(playerId, player.hasPermissions(operatorPermissionLevel));
        }
        // Not the player's operator state: an agent does not become an operator by speaking in an operator's session.
        return ToolCaller.agentFor(module, playerId, false, PROXY_AUTONOMY);
    }
}