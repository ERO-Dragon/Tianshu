package com.rheinmetal.tianshu.lab.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.EntityActor;
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
 * Brings one of the bench's own bodies into the world through the authoritative side.
 *
 * <p>This is the shape a real entity mod has: the mod is installed on both sides, the entity belongs to the server,
 * and the client <em>asks</em> for it — it never spawns one itself. The tool therefore runs on the server (its category
 * is a server-side one, so a client call is forwarded over the tool channel) and answers with the id the caller then
 * uses to look the body up through the ordinary {@code entity.*} tools.</p>
 */
public final class SummonBodyTool implements TianshuTool<JsonObject, SummonBodyTool.Result> {

    public static final ToolId ID = ToolId.of(LabModuleRegistration.MOD_ID, "summon_body");

    /** The bench's declared carrier type, the same one the acceptance cases place. */
    public static final String DEFAULT_KIND = LabModuleRegistration.MOD_ID + ":acceptance_orb";

    private static final long CALL_TIMEOUT_SECONDS = 10L;

    /** The handle the caller keeps: the body's id and where it was put. */
    public record Result(String bodyId, String dimension) {
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
        return "Creates one of the bench's own bodies at a position and returns its id.";
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
        properties.add("dimension", property("string", "Dimension id, for example minecraft:overworld."));
        properties.add("x", property("number", "World x."));
        properties.add("y", property("number", "World y."));
        properties.add("z", property("number", "World z."));
        properties.add("kind", property("string", "Declared body type; defaults to the bench's acceptance_orb."));
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("dimension");
        required.add("x");
        required.add("y");
        required.add("z");
        schema.add("required", required);
        return schema;
    }

    @Override
    public CompletableFuture<Result> invoke(ToolContext context, JsonObject args) {
        EntitySpawnPort port = TianshuRuntimePorts.find(EntitySpawnPort.class).orElse(null);
        if (port == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "no authoritative body registry on this side: bodies are created by the server"));
        }
        ActorPosition at;
        String kind;
        try {
            String dimension = args.get("dimension").getAsString();
            at = new ActorPosition(dimension, args.get("x").getAsDouble(),
                    args.get("y").getAsDouble(), args.get("z").getAsDouble());
            kind = args.has("kind") && !args.get("kind").isJsonNull()
                    ? args.get("kind").getAsString()
                    : DEFAULT_KIND;
        } catch (RuntimeException malformed) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "dimension, x, y and z are required: " + malformed.getMessage()));
        }
        return port.spawn(LabModuleRegistration.MOD_ID, kind, at, false)
                .thenApply(actor -> new Result(actor.id(), at.dimension()))
                .orTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static JsonObject property(String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        property.addProperty("description", description);
        return property;
    }
}