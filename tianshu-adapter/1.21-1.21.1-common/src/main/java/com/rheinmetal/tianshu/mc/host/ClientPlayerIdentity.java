package com.rheinmetal.tianshu.mc.host;

import com.rheinmetal.tianshu.api.assembly.LocalPlayerIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.Optional;

/**
 * The local player, read from the client at call time.
 *
 * <p>Client-only by construction: it is registered from the loaders' client entry points, so a dedicated server
 * never loads this class.</p>
 */
public final class ClientPlayerIdentity implements LocalPlayerIdentity {

    private static final int OPERATOR_LEVEL = 2;

    @Override
    public Optional<String> playerId() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? Optional.empty() : Optional.of(player.getUUID().toString());
    }

    @Override
    public boolean operator() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.hasPermissions(OPERATOR_LEVEL);
    }
}