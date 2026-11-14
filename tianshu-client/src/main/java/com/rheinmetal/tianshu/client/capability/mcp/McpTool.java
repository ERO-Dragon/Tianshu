package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/** One remote MCP tool, presented to the agent as a normal local tool. */
final class McpTool implements TianshuTool<JsonObject, JsonObject> {

    private final ToolId id;
    private final McpClient client;
    private final String remoteName;
    private final String description;
    private final JsonObject schema;
    private final ToolAutonomy autonomy;
    private final String serverName;

    McpTool(String serverName, ToolId id, McpClient client, String remoteName, String description,
            JsonObject schema, ToolAutonomy autonomy) {
        this.serverName = serverName;
        this.id = id;
        this.client = client;
        this.remoteName = remoteName;
        this.description = description;
        this.schema = schema;
        this.autonomy = autonomy;
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
            } catch (Exception failure) {
                JsonObject error = new JsonObject();
                error.addProperty("ok", false);
                error.addProperty("code", "FAILED");
                error.addProperty("message", String.valueOf(failure.getMessage()));
                return error;
            }
        });
    }
}
