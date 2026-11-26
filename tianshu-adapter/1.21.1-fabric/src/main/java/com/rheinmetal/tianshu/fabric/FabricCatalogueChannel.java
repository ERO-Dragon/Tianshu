package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.tool.ToolCatalogEntry;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;
import com.rheinmetal.tianshu.mc.tool.ServerCatalogueChannel;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Fabric's half of catalogue delivery.
 *
 * <p>Fabric lets a peer announce play channels after JOIN, so "not ready" is a normal, temporary answer here — which
 * is exactly why the session asks again on the next tick instead of treating the first answer as final.</p>
 */
public final class FabricCatalogueChannel implements ServerCatalogueChannel {

    @Override
    public boolean ready(ServerPlayer player) {
        return player.connection != null && ServerPlayNetworking.canSend(player, ToolCatalogPacket.TYPE);
    }

    @Override
    public void send(ServerPlayer player, List<ToolCatalogEntry> tools) {
        if (player.level().getServer() == null) {
            return;
        }
        player.level().getServer().execute(() -> {
            if (ready(player)) {
                ServerPlayNetworking.send(player, new ToolCatalogPacket(tools));
            }
        });
    }
}