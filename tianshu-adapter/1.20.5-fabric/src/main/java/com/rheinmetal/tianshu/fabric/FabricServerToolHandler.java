package com.rheinmetal.tianshu.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.LootQueryPort;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolSpec;
import com.rheinmetal.tianshu.client.tool.BlockInteractTool;
import com.rheinmetal.tianshu.client.tool.BlockMachineTool;
import com.rheinmetal.tianshu.client.tool.ContainerTransferTool;
import com.rheinmetal.tianshu.client.tool.CraftRecipeTool;
import com.rheinmetal.tianshu.client.tool.LootQueryTool;
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallResultPacket;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;

import com.rheinmetal.tianshu.mc.tool.BlockInteractSource;
import com.rheinmetal.tianshu.mc.tool.BlockMachineSource;
import com.rheinmetal.tianshu.mc.tool.ContainerTransferSource;
import com.rheinmetal.tianshu.mc.tool.CraftSource;
import com.rheinmetal.tianshu.mc.tool.LootQuerySource;
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
            FabricToolConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
            TianshuRuntimePorts.register(LootQueryPort.class, new LootQuerySource(server));
            TianshuRuntimePorts.register(BlockInteractPort.class, new BlockInteractSource(server));
            TianshuRuntimePorts.register(CraftPort.class, new CraftSource(server));
            TianshuRuntimePorts.register(ContainerTransferPort.class, new ContainerTransferSource(server));
            TianshuRuntimePorts.register(BlockMachinePort.class, new BlockMachineSource(server));
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
        ServerPlayNetworking.registerGlobalReceiver(ToolCallRequestPacket.TYPE, (packet, context) -> {
            ServerPlayer player = context.player();
            if (player != null && player.getServer() != null) {
                player.getServer().execute(() -> respond(player, packet));
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) {
                ServerPlayNetworking.send(player, new ToolCatalogPacket(serverToolIds(player)));
            }
        });
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

    private static void respond(ServerPlayer player, ToolCallRequestPacket packet) {
        if (player == null || player.connection == null) {
            return;
        }
        ToolId id;
        try {
            id = ToolId.parse(packet.toolId());
        } catch (IllegalArgumentException malformed) {
            send(player, failure(packet.requestId(), ToolError.UNKNOWN_TOOL, "malformed tool id"));
            return;
        }
        JsonElement args;
        try {
            args = JsonParser.parseString(packet.argsJson());
        } catch (RuntimeException malformed) {
            send(player, failure(packet.requestId(), ToolError.INVALID_ARGS, "malformed arguments"));
            return;
        }
        ToolCaller caller = ToolCaller.player(player.getUUID().toString(),
                player.hasPermissions(OPERATOR_PERMISSION_LEVEL));
        TianshuTools.callJson(id, args, caller, ToolSide.SERVER)
                .thenAccept(envelope -> send(player, toResult(packet.requestId(), envelope)))
                .exceptionally(thrown -> {
                    send(player, failure(packet.requestId(), ToolError.FAILED, String.valueOf(thrown)));
                    return null;
                });
    }

    private static ToolCallResultPacket toResult(String requestId, JsonObject envelope) {
        if (envelope.get("ok").getAsBoolean()) {
            return new ToolCallResultPacket(requestId, true, envelope.get("result").toString(), "", "");
        }
        return new ToolCallResultPacket(requestId, false, "",
                envelope.get("code").getAsString(), envelope.get("message").getAsString());
    }

    private static ToolCallResultPacket failure(String requestId, ToolError code, String message) {
        return new ToolCallResultPacket(requestId, false, "", code.name(), message);
    }

    private static void send(ServerPlayer player, ToolCallResultPacket result) {
        if (player.connection == null || !ServerPlayNetworking.canSend(player, ToolCallResultPacket.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, result);
    }
}