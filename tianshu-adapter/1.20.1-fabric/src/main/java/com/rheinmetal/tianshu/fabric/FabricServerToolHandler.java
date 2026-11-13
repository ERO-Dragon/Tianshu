package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.LootQueryPort;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ServerToolDispatch;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolSpec;
import com.rheinmetal.tianshu.client.tool.BlockInteractTool;
import com.rheinmetal.tianshu.client.tool.ContainerTransferTool;
import com.rheinmetal.tianshu.client.tool.LootQueryTool;
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallResultPacket;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;
import com.rheinmetal.tianshu.mc.tool.BlockInteractSource;
import com.rheinmetal.tianshu.mc.tool.ContainerTransferSource;
import com.rheinmetal.tianshu.mc.tool.LootQuerySource;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Consumer;

/** The server half of the legacy Fabric tool channel: loot / block interact / container transfer. */
public final class FabricServerToolHandler {

    private FabricServerToolHandler() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ToolCallRequestPacket.CHANNEL,
                (server, player, handler, buffer, responseSender) -> {
                    ToolCallRequestPacket packet = ToolCallRequestPacket.decode(buffer);
                    server.execute(() -> respond(player, packet));
                });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                send(handler.getPlayer(), ToolCatalogPacket.CHANNEL, new ToolCatalogPacket(serverToolIds(handler.getPlayer()))));
        installTools();
    }

    private static void installTools() {
        // Ports are installed on SERVER_STARTING, not here: they need the running server.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            FabricToolConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
            TianshuRuntimePorts.register(LootQueryPort.class, new LootQuerySource(server));
            TianshuRuntimePorts.register(BlockInteractPort.class, new BlockInteractSource(server));
            TianshuRuntimePorts.register(ContainerTransferPort.class, new ContainerTransferSource(server));
            register(new LootQueryTool());
            register(new BlockInteractTool());
            register(new ContainerTransferTool());
            TianshuTools.setEnabled(BlockInteractTool.ID, FabricToolConfig.blockInteract());
            TianshuTools.setEnabled(ContainerTransferTool.ID, FabricToolConfig.containerTransfer());
        });
    }

    private static void register(TianshuTool<?, ?> tool) {
        if (TianshuTools.find(tool.id()) == null) {
            TianshuTools.register(tool);
        }
    }

    private static List<String> serverToolIds(ServerPlayer player) {
        if (player == null) {
            return List.of();
        }
        ToolCaller caller = ToolCaller.player(player.getUUID().toString(), player.hasPermissions(2));
        return TianshuTools.catalog(caller, ToolSide.SERVER).stream()
                .filter(spec -> spec.category().isServerSide())
                .map(ToolSpec::id)
                .map(ToolId::toString)
                .toList();
    }
    private static void respond(ServerPlayer player, ToolCallRequestPacket packet) {
        if (player == null) {
            return;
        }
        ToolCaller caller = ToolCaller.player(player.getUUID().toString(), player.hasPermissions(2));
        ServerToolDispatch.respond(caller, packet.requestId(), packet.toolId(), packet.argsJson())
                .thenAccept(reply -> send(player, ToolCallResultPacket.CHANNEL, new ToolCallResultPacket(
                        reply.requestId(), reply.ok(), reply.valueJson(), reply.errorCode(), reply.message())));
    }

            /** 1.20.x Fabric sends a raw buffer per channel; the packet only knows how to write itself. */
    private static void send(ServerPlayer player, net.minecraft.resources.ResourceLocation channel,
                             ToolCallResultPacket packet) {
        if (player == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.connection != null && ServerPlayNetworking.canSend(player, channel)) {
                FriendlyByteBuf buffer = PacketByteBufs.create();
                packet.encode(buffer);
                ServerPlayNetworking.send(player, channel, buffer);
            }
        });
    }
    private static void send(ServerPlayer player, net.minecraft.resources.ResourceLocation channel,
                             ToolCatalogPacket packet) {
        if (player == null) {
            return;
        }
        if (ServerPlayNetworking.canSend(player, channel)) {
            FriendlyByteBuf buffer = PacketByteBufs.create();
            packet.encode(buffer);
            ServerPlayNetworking.send(player, channel, buffer);
        }
    }
}
