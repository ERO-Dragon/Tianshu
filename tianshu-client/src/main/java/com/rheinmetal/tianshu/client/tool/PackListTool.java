package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.client.host.settings.ClientPackSettingsPort;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The resource packs installed on this client, and which of them are on. */
public final class PackListTool implements TianshuTool<EmptyArgs, List<ClientPackSettingsPort.Pack>> {

    public static final ToolId ID = ToolId.of("tianshu", "client.pack.list");

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
        return "Lists the resource packs installed on this client with their ids, labels and whether each is enabled. "
                + "Packs marked required cannot be switched off.";
    }

    @Override
    public Type argsType() {
        return EmptyArgs.class;
    }

    @Override
    public Type resultType() {
        return List.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public CompletableFuture<List<ClientPackSettingsPort.Pack>> invoke(ToolContext context, EmptyArgs args) {
        ClientPackSettingsPort port = TianshuRuntimePorts.find(ClientPackSettingsPort.class).orElse(null);
        if (port == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNSUPPORTED_SIDE,
                    "this platform does not expose its resource packs"));
        }
        return CompletableFuture.completedFuture(port.packs());
    }
}