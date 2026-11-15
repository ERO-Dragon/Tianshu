package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.agent.AgentActionPort;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
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
import com.rheinmetal.tianshu.mc.body.AgentActionSource;
import com.rheinmetal.tianshu.mc.body.AgentMotionSource;
import com.rheinmetal.tianshu.mc.body.BodyCommands;
import com.rheinmetal.tianshu.mc.body.BodyRegistry;
import com.rheinmetal.tianshu.mc.body.BodyRuntime;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * The server half of the tool channel: announces which server-side tools exist and answers calls.
 *
 * <p>Every call goes through the same registry and policy checks as a local one, with the caller carrying the
 * requesting player's identity and operator state.</p>
 */
public final class FabricServerToolHandler {

    private static final int OPERATOR_PERMISSION_LEVEL = 2;

    private FabricServerToolHandler() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            BodyRegistry bodies = BodyRuntime.start();
            FabricToolConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
            TianshuRuntimePorts.register(LootQueryPort.class, new LootQuerySource(server));
            TianshuRuntimePorts.register(RecipeQueryPort.class, new ServerRecipeQuerySource(server));
            if (TianshuTools.find(RecipeQueryTool.ID) == null) {
                TianshuTools.register(new RecipeQueryTool());
            }
            TianshuRuntimePorts.register(BlockInteractPort.class, new BlockInteractSource(server));
            TianshuRuntimePorts.register(CraftPort.class, new CraftSource(server));
            TianshuRuntimePorts.register(ContainerTransferPort.class, new ContainerTransferSource(server));
            TianshuRuntimePorts.register(BlockMachinePort.class, new BlockMachineSource(server));
            TianshuRuntimePorts.register(AgentMotionPort.class, new AgentMotionSource(server, bodies));
            TianshuRuntimePorts.register(AgentActionPort.class, new AgentActionSource(server, bodies));
            if (TianshuTools.find(LootQueryTool.ID) == null) {
                TianshuTools.register(new LootQueryTool());
            }
            if (TianshuTools.find(BlockInteractTool.ID) == null) {
                TianshuTools.register(new BlockInteractTool());
            }
            if (TianshuTools.find(CraftRecipeTool.ID) == null) {
                TianshuTools.register(new CraftRecipeTool());
            }
            TianshuTools.setEnabled(BlockInteractTool.ID, FabricToolConfig.blockInteract());
            if (TianshuTools.find(ContainerTransferTool.ID) == null) {
                TianshuTools.register(new ContainerTransferTool());
            }
            TianshuTools.setEnabled(CraftRecipeTool.ID, FabricToolConfig.craftRecipe());
            if (TianshuTools.find(BlockMachineTool.ID) == null) {
                TianshuTools.register(new BlockMachineTool());
            }
            TianshuTools.setEnabled(ContainerTransferTool.ID, FabricToolConfig.containerTransfer());
            TianshuTools.setEnabled(BlockMachineTool.ID, FabricToolConfig.blockMachine());
        });
        ServerPlayNetworking.registerGlobalReceiver(ToolCallRequestPacket.TYPE, (packet, context) ->
                context.server().execute(() -> respond(context.player(), packet)));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onPlayerLogin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onPlayerLogout(handler.getPlayer()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> BodyRuntime.stop());
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                BodyCommands.register(dispatcher));
    }

    private static void onPlayerLogin(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        String playerId = player.getUUID().toString();
        boolean operator = player.hasPermissions(OPERATOR_PERMISSION_LEVEL);
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
        if (player.connection == null || !ServerPlayNetworking.canSend(player, ToolCallResultPacket.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, result);
    }
}