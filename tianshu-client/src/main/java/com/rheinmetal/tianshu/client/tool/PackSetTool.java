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
import java.util.concurrent.CompletableFuture;

/** Switches one resource pack on or off. */
public final class PackSetTool implements TianshuTool<PackSetTool.Args, ClientPackSettingsPort.Pack> {

    public static final ToolId ID = ToolId.of("tianshu", "client.pack.set");

    public record Args(String pack, boolean enabled) {
    }

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
        return "Enables or disables one resource pack by id and applies it. The client reloads its resources, which takes "
                + "a moment. Required packs are refused.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return ClientPackSettingsPort.Pack.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("pack", BodyToolSchemas.text("Pack id from client.pack.list, for example vanilla"));
        JsonObject enabled = new JsonObject();
        enabled.addProperty("type", "boolean");
        enabled.addProperty("description", "True to enable, false to disable");
        properties.add("enabled", enabled);
        return BodyToolSchemas.object(properties, "pack", "enabled");
    }

    @Override
    public CompletableFuture<ClientPackSettingsPort.Pack> invoke(ToolContext context, Args args) {
        ClientPackSettingsPort port = TianshuRuntimePorts.find(ClientPackSettingsPort.class).orElse(null);
        if (port == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNSUPPORTED_SIDE,
                    "this platform does not expose its resource packs"));
        }
        if (args == null || args.pack() == null || args.pack().isBlank()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "client.pack.set needs a pack id"));
        }
        String pack = args.pack().trim();
        if (!port.setEnabled(pack, args.enabled())) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "cannot change resource pack " + pack
                            + ": it is unknown or required; client.pack.list names the ones this client has"));
        }
        return CompletableFuture.completedFuture(port.packs().stream()
                .filter(candidate -> candidate.id().equals(pack))
                .findFirst()
                .orElse(new ClientPackSettingsPort.Pack(pack, "", args.enabled(), false)));
    }
}