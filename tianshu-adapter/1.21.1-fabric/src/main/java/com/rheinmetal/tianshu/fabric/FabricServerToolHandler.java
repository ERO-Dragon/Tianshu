package com.rheinmetal.tianshu.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolSpec;
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallResultPacket;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;

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
        ServerPlayNetworking.registerGlobalReceiver(ToolCallRequestPacket.TYPE, (packet, context) ->
                context.server().execute(() -> respond(context.player(), packet)));
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