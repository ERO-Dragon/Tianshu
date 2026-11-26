package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityQueryPort;
import com.rheinmetal.tianshu.api.entity.ScanRequest;
import com.rheinmetal.tianshu.api.entity.ScanResult;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Looks around an actor: nearby blocks of a given id, nearby entities of a given type.
 *
 * <p>Read-only. A call must name at least one filter; there is no "give me everything" mode, and the adapter
 * clamps the radius and the result count.</p>
 */
public final class BodyScanTool implements TianshuTool<BodyScanTool.Args, ScanResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.scan");

    private static final int DEFAULT_RADIUS = 8;
    private static final int DEFAULT_LIMIT = 64;

    public record Args(String body, int radius, int limit, String blocks, String entities) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.BODY_READ;
    }

    @Override
    public String description() {
        return "Scans around an actor for blocks of one id and/or entities of one type, within a radius.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return ScanResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("radius", BodyToolSchemas.integer("Radius in blocks; defaults to 8, clamped by the server"));
        properties.add("limit", BodyToolSchemas.integer("Maximum hits per kind; defaults to 64"));
        properties.add("blocks", BodyToolSchemas.text("Exact block id to find, for example minecraft:stone"));
        properties.add("entities", BodyToolSchemas.text("Exact entity type id, for example minecraft:zombie"));
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<ScanResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.body(caller, args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        String blocks = args == null || args.blocks() == null ? "" : args.blocks().trim();
        String entities = args == null || args.entities() == null ? "" : args.entities().trim();
        if (blocks.isEmpty() && entities.isEmpty()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.scan needs a block id or an entity type id"));
        }
        int radius = args.radius() > 0 ? args.radius() : DEFAULT_RADIUS;
        int limit = args.limit() > 0 ? args.limit() : DEFAULT_LIMIT;
        ActorRef actor = scope.actor();
        return TianshuRuntimePorts.find(EntityQueryPort.class)
                .map(port -> port.scan(actor, new ScanRequest(radius, limit, blocks, entities)))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "world scanning is only available on the server")));
    }
}