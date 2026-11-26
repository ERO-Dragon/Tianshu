package com.rheinmetal.tianshu.neoforge.network;

import com.rheinmetal.tianshu.api.tool.ToolCatalogEntry;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;
import com.rheinmetal.tianshu.mc.tool.ServerCatalogueChannel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * NeoForge's half of catalogue delivery: the client has to have announced the channel, and sending happens on the
 * server thread.
 *
 * <p>Both halves are the framework's own answer to "can this player take a packet right now", so the session can ask
 * on every tick without stepping over a half-negotiated connection.</p>
 */
public final class NeoForgeCatalogueChannel implements ServerCatalogueChannel {

    @Override
    public boolean ready(ServerPlayer player) {
        return player.connection != null && player.connection.hasChannel(ToolCatalogPacket.TYPE);
    }

    @Override
    public void send(ServerPlayer player, List<ToolCatalogEntry> tools) {
        if (player.level().getServer() == null) {
            return;
        }
        player.level().getServer().execute(() -> {
            if (ready(player)) {
                PacketDistributor.sendToPlayer(player, new ToolCatalogPacket(tools));
            }
        });
    }
}