package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** One remote MCP tool, presented to the agent as a normal local tool. */
final class McpTool implements TianshuTool<JsonObject, JsonObject> {

    private final ToolId id;
    private final McpConnection client;
    private final String remoteName;
    private final String description;
    private final JsonObject schema;
    private final ToolAutonomy autonomy;
    private final String serverName;
    private final Consumer<String> transportFailure;

    McpTool(String serverName, ToolId id, McpConnection client, String remoteName, String description,
            JsonObject schema, ToolAutonomy autonomy, Consumer<String> transportFailure) {
        this.serverName = serverName;
        this.id = id;
        this.client = client;
        this.remoteName = remoteName;
        this.description = description;
        this.schema = schema;
        this.autonomy = autonomy;
        this.transportFailure = transportFailure;
    }

    @Override
    public ToolId id() {
        return id;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.LOCAL;
    }

    @Override
    public String description() {
        return "MCP " + serverName + ": " + description;
    }

    @Override
    public ToolAutonomy requiredAutonomy() {
        return autonomy;
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
        return schema == null ? null : schema.deepCopy();
    }

    @Override
    public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return client.callTool(remoteName, args == null ? new JsonObject() : args);
            } catch (McpRemoteException rejected) {
                // The server is fine; this one call was rejected.
                JsonObject error = new JsonObject();
                error.addProperty("ok", false);
                error.addProperty("code", "FAILED");
                error.addProperty("message", String.valueOf(rejected.getMessage()));
                return error;
            } catch (Exception failure) {
                transportFailure.accept(serverName);
                JsonObject error = new JsonObject();
                error.addProperty("ok", false);
                error.addProperty("code", "UNAVAILABLE");
                error.addProperty("message", String.valueOf(failure.getMessage()));
                return error;
            }
        });
    }
}
