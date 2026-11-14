package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.LootQueryPort;
import com.rheinmetal.tianshu.api.game.RecipeQueryPort;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ServerToolDispatch;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCatalogPushRegistry;
import com.rheinmetal.tianshu.client.tool.BlockInteractTool;
import com.rheinmetal.tianshu.client.tool.BlockMachineTool;
import com.rheinmetal.tianshu.client.tool.ContainerTransferTool;
import com.rheinmetal.tianshu.client.tool.CraftRecipeTool;
import com.rheinmetal.tianshu.client.tool.LootQueryTool;
import com.rheinmetal.tianshu.client.tool.RecipeQueryTool;
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallResultPacket;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;
import com.rheinmetal.tianshu.mc.tool.BlockInteractSource;
import com.rheinmetal.tianshu.mc.tool.BlockMachineSource;
import com.rheinmetal.tianshu.mc.tool.ContainerTransferSource;
import com.rheinmetal.tianshu.mc.tool.CraftSource;
import com.rheinmetal.tianshu.mc.tool.LootQuerySource;
import com.rheinmetal.tianshu.mc.tool.ServerRecipeQuerySource;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * The server half of the tool channel: announces the server-side tools that exist here and answers calls.
 *
 * <p>This generation has no recipe/display access that would let craft.recipe read a recipe's ingredients, and its
 * fuel API differs from 1.21.1, so this line offers loot.query, block.interact and container.transfer only.</p>
 */
public final class FabricServerToolHandler {

    private static final int OPERATOR_PERMISSION_LEVEL = 2;

    private FabricServerToolHandler() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(FabricServerToolHandler::onServerStarting);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onPlayerLogin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onPlayerLogout(handler.getPlayer()));
    }

    private static void onServerStarting(MinecraftServer server) {
        onServerStarting0(server);
    }

    private static void onServerStarting0(MinecraftServer server) {
        TianshuRuntimePorts.register(LootQueryPort.class, new LootQuerySource(server));
    TianshuRuntimePorts.register(RecipeQueryPort.class, new ServerRecipeQuerySource(server));
    if (TianshuTools.find(RecipeQueryTool.ID) == null) {
        TianshuTools.register(new RecipeQueryTool());
    }
        TianshuRuntimePorts.register(BlockInteractPort.class, new BlockInteractSource(server));
        TianshuRuntimePorts.register(ContainerTransferPort.class, new ContainerTransferSource(server));
    TianshuRuntimePorts.register(CraftPort.class, new CraftSource(server));
    TianshuRuntimePorts.register(BlockMachinePort.class, new BlockMachineSource(server));
    if (TianshuTools.find(CraftRecipeTool.ID) == null) {
        TianshuTools.register(new CraftRecipeTool());
    }
    if (TianshuTools.find(BlockMachineTool.ID) == null) {
        TianshuTools.register(new BlockMachineTool());
    }
    TianshuTools.setEnabled(CraftRecipeTool.ID, FabricToolConfig.craftRecipe());
    TianshuTools.setEnabled(BlockMachineTool.ID, FabricToolConfig.blockMachine());
        register(new LootQueryTool());
        register(new BlockInteractTool());
        register(new ContainerTransferTool());
        TianshuTools.setEnabled(BlockInteractTool.ID, FabricToolConfig.blockInteract());
        TianshuTools.setEnabled(ContainerTransferTool.ID, FabricToolConfig.containerTransfer());
    }

    private static void register(com.rheinmetal.tianshu.api.tool.TianshuTool<?, ?> tool) {
        if (TianshuTools.find(tool.id()) == null) {
            TianshuTools.register(tool);
        }
    }

    private static void onPlayerLogin(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        String playerId = player.getUUID().toString();
        boolean operator = player.hasPermissions(2);
        ToolCatalogPushRegistry.register(playerId, operator, (ignored, ids) -> sendCatalog(player, ids));
    }

    private static void onPlayerLogout(ServerPlayer player) {
        if (player != null) {
            ToolCatalogPushRegistry.unregister(player.getUUID().toString());
        }
    }

    private static void sendCatalog(ServerPlayer player, List<String> ids) {
        var server = player.level().getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.connection != null) {
                ServerPlayNetworking.send(player, new ToolCatalogPacket(ids));
            }
        });
    }


    public static void handle(ToolCallRequestPacket packet, ServerPlayer player) {
        if (player == null) {
            return;
        }
        respond(player, packet);
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
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.connection != null && ServerPlayNetworking.canSend(player, ToolCallResultPacket.TYPE)) {
                ServerPlayNetworking.send(player, result);
            }
        });
    }
}
