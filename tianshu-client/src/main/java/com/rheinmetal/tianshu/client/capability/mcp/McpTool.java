package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    private final Executor calls;

    McpTool(String serverName, ToolId id, McpConnection client, String remoteName, String description,
            JsonObject schema, ToolAutonomy autonomy, Consumer<String> transportFailure, Executor calls) {
        this.serverName = serverName;
        this.id = id;
        this.client = client;
        this.remoteName = remoteName;
        this.description = description;
        this.schema = schema;
        this.autonomy = autonomy;
        this.transportFailure = transportFailure;
        this.calls = calls;
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
                // The server is fine; this one call was rejected. Fail the future so the platform's envelope
                // stays flat ({"ok":false,...}) — returning a nested error object would be wrapped by
                // TianshuTools.successEnvelope and reach the model as a success.
                throw new ToolException(ToolError.FAILED,
                        "MCP " + serverName + " rejected " + remoteName + ": " + rejected.getMessage());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ToolException(ToolError.CANCELLED, "the MCP call was interrupted");
            } catch (IOException transport) {
                // Only a transport failure takes the server's tools offline; a bad argument or a malformed
                // payload is a per-call problem.
                transportFailure.accept(serverName);
                throw new ToolException(ToolError.UNAVAILABLE,
                        "MCP " + serverName + " is unreachable: " + transport.getMessage());
            }
        }, calls);
    }
}
