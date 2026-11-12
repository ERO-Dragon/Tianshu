package com.rheinmetal.tianshu.neoforge.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.LootQueryPort;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
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
import com.rheinmetal.tianshu.neoforge.config.ServerActionConfig;
import com.rheinmetal.tianshu.neoforge.network.TianshuNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.bus.api.IEventBus;

import java.util.List;

/** The server half of the 1.20.2/1.20.4 tool channel: loot / block interact / container transfer. */
public final class ServerToolHandler {

    private ServerToolHandler() {
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(ServerToolHandler::onPlayerLogin);
        gameBus.addListener(ServerToolHandler::onServerStarting);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        TianshuRuntimePorts.register(LootQueryPort.class, new LootQuerySource(event.getServer()));
        TianshuRuntimePorts.register(BlockInteractPort.class, new BlockInteractSource(event.getServer()));
        TianshuRuntimePorts.register(ContainerTransferPort.class, new ContainerTransferSource(event.getServer()));
        register(new LootQueryTool());
        register(new BlockInteractTool());
        register(new ContainerTransferTool());
        TianshuTools.setEnabled(BlockInteractTool.ID, ServerActionConfig.BLOCK_INTERACT.get());
        TianshuTools.setEnabled(ContainerTransferTool.ID, ServerActionConfig.CONTAINER_TRANSFER.get());
    }

    private static void register(TianshuTool<?, ?> tool) {
        if (TianshuTools.find(tool.id()) == null) {
            TianshuTools.register(tool);
        }
    }

    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        TianshuNetwork.sendToolCatalog(player, new ToolCatalogPacket(serverToolIds(player)));
    }

    private static List<String> serverToolIds(ServerPlayer player) {
        ToolCaller caller = ToolCaller.player(player.getUUID().toString(), player.hasPermissions(2));
        return TianshuTools.catalog(caller, ToolSide.SERVER).stream()
                .filter(spec -> spec.category().isServerSide())
                .map(ToolSpec::id)
                .map(ToolId::toString)
                .toList();
    }

    public static void respond(ServerPlayer player, ToolCallRequestPacket packet) {
        if (player == null) {
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
        ToolCaller caller = ToolCaller.player(player.getUUID().toString(), player.hasPermissions(2));
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
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.connection != null) {
                TianshuNetwork.sendToolResult(player, result);
            }
        });
    }
}
