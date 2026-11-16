package com.rheinmetal.tianshu.neoforge.server;

import com.rheinmetal.tianshu.api.server.ServerActionExecutionPort;
import com.rheinmetal.tianshu.api.server.ServerActionResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

/**
 * Carries out a whitelisted action for the calling player, on the server.
 *
 * <p>The tool layer knows the arguments; this class knows where the player is. All the gates stay in
 * {@link ServerActionHandler}: the action's switch, the operator check and the argument validation.</p>
 */
public final class NeoForgeActionExecution implements ServerActionExecutionPort {

    private final MinecraftServer server;

    public NeoForgeActionExecution(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public ServerActionResult execute(String actorId, String action, Map<String, String> params) {
        ServerPlayer player;
        try {
            player = server.getPlayerList().getPlayer(UUID.fromString(actorId));
        } catch (IllegalArgumentException notAUuid) {
            player = null;
        }
        if (player == null) {
            return ServerActionResult.of(ServerActionResult.Status.FAILED,
                    "the caller is not a player on this server");
        }
        return ServerActionHandler.execute(player, action, params);
    }
}