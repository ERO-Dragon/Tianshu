package com.rheinmetal.tianshu.neoforge.server;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.LootQueryPort;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ServerToolDispatch;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolSpec;
import com.rheinmetal.tianshu.client.tool.BlockInteractTool;
import com.rheinmetal.tianshu.client.tool.BlockMachineTool;
import com.rheinmetal.tianshu.client.tool.ContainerTransferTool;
import com.rheinmetal.tianshu.client.tool.CraftRecipeTool;
import com.rheinmetal.tianshu.client.tool.LootQueryTool;
import com.rheinmetal.tianshu.neoforge.config.ServerActionConfig;
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallResultPacket;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import com.rheinmetal.tianshu.mc.tool.BlockInteractSource;
import com.rheinmetal.tianshu.mc.tool.BlockMachineSource;
import com.rheinmetal.tianshu.mc.tool.ContainerTransferSource;
import com.rheinmetal.tianshu.mc.tool.CraftSource;
import com.rheinmetal.tianshu.mc.tool.LootQuerySource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * The server half of the tool channel: announces which server-side tools exist and answers calls.
 *
 * <p>It never invents results: every call goes through the same registry and policy checks as a local one, with the
 * caller carrying the requesting player's identity and operator state, so a server tool sees exactly the permission
 * the player has.</p>
 */
public final class ServerToolHandler {

    private static final int OPERATOR_PERMISSION_LEVEL = 2;

    private ServerToolHandler() {
    }

    /** Sends the catalog when a player joins, like the action channel does. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(ServerToolHandler::onPlayerLogin);
        gameBus.addListener(ServerToolHandler::onServerStarting);
    }

    /** Installs the server-side tool sources and registers the tools they back. */
    private static void onServerStarting(ServerStartingEvent event) {
        TianshuRuntimePorts.register(LootQueryPort.class, new LootQuerySource(event.getServer()));
        TianshuRuntimePorts.register(BlockInteractPort.class, new BlockInteractSource(event.getServer()));
        TianshuRuntimePorts.register(CraftPort.class, new CraftSource(event.getServer()));
        TianshuRuntimePorts.register(ContainerTransferPort.class,
                new ContainerTransferSource(event.getServer()));
        TianshuRuntimePorts.register(BlockMachinePort.class, new BlockMachineSource(event.getServer()));
        if (TianshuTools.find(LootQueryTool.ID) == null) {
            TianshuTools.register(new LootQueryTool());
        }
        if (TianshuTools.find(BlockInteractTool.ID) == null) {
            TianshuTools.register(new BlockInteractTool());
        }
        if (TianshuTools.find(CraftRecipeTool.ID) == null) {
            TianshuTools.register(new CraftRecipeTool());
        }
        TianshuTools.setEnabled(BlockInteractTool.ID, ServerActionConfig.BLOCK_INTERACT.get());
        if (TianshuTools.find(ContainerTransferTool.ID) == null) {
            TianshuTools.register(new ContainerTransferTool());
        }
        TianshuTools.setEnabled(CraftRecipeTool.ID, ServerActionConfig.CRAFT_RECIPE.get());
        if (TianshuTools.find(BlockMachineTool.ID) == null) {
            TianshuTools.register(new BlockMachineTool());
        }
        TianshuTools.setEnabled(ContainerTransferTool.ID, ServerActionConfig.CONTAINER_TRANSFER.get());
        TianshuTools.setEnabled(BlockMachineTool.ID, ServerActionConfig.BLOCK_MACHINE.get());
    }

    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.connection == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new ToolCatalogPacket(serverToolIds(player)));
    }

    private static List<String> serverToolIds(ServerPlayer player) {
        ToolCaller caller = ToolCaller.player(player.getUUID().toString(),
                player.hasPermissions(OPERATOR_PERMISSION_LEVEL));
        return TianshuTools.catalog(caller, ToolSide.SERVER).stream()
                .filter(spec -> spec.category().isServerSide())
                .map(ToolSpec::id)
                .map(ToolId::toString)
                .toList();
    }

    /** Called on the network thread for one request; the work is moved to the server thread. */
    public static void handle(ToolCallRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> respond(player, packet));
    }
    private static void respond(ServerPlayer player, ToolCallRequestPacket packet) {
        if (player == null) {
            return;
        }
        ToolCaller caller = ToolCaller.player(player.getUUID().toString(), player.hasPermissions(OPERATOR_PERMISSION_LEVEL));
        ServerToolDispatch.respond(caller, packet.requestId(), packet.toolId(), packet.argsJson())
                .thenAccept(reply -> send(player, new ToolCallResultPacket(reply.requestId(), reply.ok(),
                        reply.valueJson(), reply.errorCode(), reply.message())));
    }
    private static void send(ServerPlayer player, ToolCallResultPacket result) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.connection != null) {
                PacketDistributor.sendToPlayer(player, result);
            }
        });
    }
}