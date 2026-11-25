package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.client.host.ClientChatPort;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Says one line in the ordinary chat channel, as the player.
 *
 * <p>A local, middle-tier tool rather than a server action: chat is what any player may do, so it needs no operator and
 * no server switch. The line travels through the game's own chat path, which means the server's chat rules apply
 * unchanged and other players see it exactly as if the player had typed it.</p>
 */
public final class SayTool implements TianshuTool<JsonObject, JsonObject> {

    public static final ToolId ID = ToolId.of("minecraft", "server.say");

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.LOCAL;
    }

    @Override
    public ToolAutonomy requiredAutonomy() {
        return ToolAutonomy.CONSERVATIVE;
    }

    @Override
    public String description() {
        return "Says one line in the ordinary chat channel as the player. Any player may chat, so this needs no "
                + "operator; the server's own chat rules still apply.";
    }

    @Override
    public Type argsType() {
        return JsonObject.class;
    }

    @Override
    public Type resultType() {
        return JsonObject.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        properties.add("text", text);
        schema.add("properties", properties);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("text");
        schema.add("required", required);
        return schema;
    }

    @Override
    public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
        var value = args == null ? null : args.get("text");
        String line = value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
        if (line.isEmpty()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS, "text must not be blank"));
        }
        ClientChatPort chat = TianshuRuntimePorts.find(ClientChatPort.class).orElse(null);
        if (chat == null) {
            return CompletableFuture.failedFuture(
                    new ToolException(ToolError.UNSUPPORTED_SIDE, "this platform cannot speak in chat"));
        }
        if (!chat.say(line)) {
            return CompletableFuture.failedFuture(
                    new ToolException(ToolError.UNAVAILABLE, "not connected to a server, so nothing could be said"));
        }
        JsonObject ok = new JsonObject();
        ok.addProperty("ok", true);
        return CompletableFuture.completedFuture(ok);
    }
}