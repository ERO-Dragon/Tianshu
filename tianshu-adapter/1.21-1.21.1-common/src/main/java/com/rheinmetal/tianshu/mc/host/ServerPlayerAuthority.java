package com.rheinmetal.tianshu.mc.host;

import com.rheinmetal.tianshu.api.assembly.PlayerAuthority;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Answers operator questions from the running server's player list. */
public final class ServerPlayerAuthority implements PlayerAuthority {

    private static final int OPERATOR_LEVEL = 2;

    private final MinecraftServer server;

    public ServerPlayerAuthority(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean operator(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return false;
        }
        UUID id;
        try {
            id = UUID.fromString(playerId.trim());
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        return player != null && player.hasPermissions(OPERATOR_LEVEL);
    }
}