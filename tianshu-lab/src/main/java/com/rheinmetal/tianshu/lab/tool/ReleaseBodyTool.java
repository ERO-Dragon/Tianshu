package com.rheinmetal.tianshu.lab.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.BodyController;
import com.rheinmetal.tianshu.api.entity.EntitySpawnPort;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.lab.LabModuleRegistration;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Takes back a body {@link SummonBodyTool} created.
 *
 * <p>The pair matters: allocation is only safe when the module that brought a body in can take it out again. It runs on
 * the server for the same reason spawning does — only the authoritative side holds the bodies, and only the module that
 * spawned one may release it.</p>
 */
public final class ReleaseBodyTool implements TianshuTool<JsonObject, ReleaseBodyTool.Result> {

    public static final ToolId ID = ToolId.of(LabModuleRegistration.MOD_ID, "release_body");

    private static final long CALL_TIMEOUT_SECONDS = 10L;

    /** What was removed, so a caller can tell the release from a silent no-op. */
    public record Result(String bodyId, boolean released) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_WRITE;
    }

    @Override
    public String description() {
        return "Removes a body this bench created and returns whether it was there.";
    }

    @Override
    public Type argsType() {
        return JsonObject.class;
    }

    @Override
    public Type resultType() {
        return Result.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        JsonObject bodyId = new JsonObject();
        bodyId.addProperty("type", "string");
        bodyId.addProperty("description", "The id summon_body returned.");
        properties.add("bodyId", bodyId);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("bodyId");
        schema.add("required", required);
        return schema;
    }

    @Override
    public CompletableFuture<Result> invoke(ToolContext context, JsonObject args) {
        EntitySpawnPort port = TianshuRuntimePorts.find(EntitySpawnPort.class).orElse(null);
        if (port == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "no authoritative body registry on this side: bodies live on the server"));
        }
        String bodyId;
        try {
            bodyId = args.get("bodyId").getAsString();
        } catch (RuntimeException malformed) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "bodyId is required"));
        }
        var controller = BodyController.forRequest(LabModuleRegistration.MOD_ID, context.caller());
        return port.release(controller, bodyId)
                .thenApply(ignored -> new Result(bodyId, true))
                .orTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}