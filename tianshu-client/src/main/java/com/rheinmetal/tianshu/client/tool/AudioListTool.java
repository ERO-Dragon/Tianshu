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
import com.rheinmetal.tianshu.client.host.settings.ClientAudioSettingsPort;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The client's volume channels and where each slider sits. */
public final class AudioListTool implements TianshuTool<EmptyArgs, List<ClientAudioSettingsPort.Channel>> {

    public static final ToolId ID = ToolId.of("minecraft", "client.audio.list");

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
        return "Lists the client's volume channels (master, music, weather, block, ...) with the percentage each one is "
                + "set to.";
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
    public CompletableFuture<List<ClientAudioSettingsPort.Channel>> invoke(ToolContext context, EmptyArgs args) {
        ClientAudioSettingsPort port = TianshuRuntimePorts.find(ClientAudioSettingsPort.class).orElse(null);
        if (port == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNSUPPORTED_SIDE,
                    "this platform does not expose its audio settings"));
        }
        return CompletableFuture.completedFuture(port.channels());
    }
}