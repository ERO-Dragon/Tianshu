package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.EntityInspectPort;
import com.rheinmetal.tianshu.api.game.EntityInspectRequest;
import com.rheinmetal.tianshu.api.game.EntityInspectResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Reads one entity: type, name, position, health, tameness, the item it holds.
 *
 * <p>The read that makes the entity write tools decidable. Feeding, taming and trading all depend on whether the target is
 * already tame, whose it is, and whether it is still alive; attacking depends on health left. Without this a caller acts
 * on entities blind, which is how "tame the cat" ends up clicking a random mob.</p>
 */
public final class EntityInspectTool implements TianshuTool<EntityInspectTool.Args, EntityInspectResult> {

    /** {@code entity} is the id {@code body.scan} reported. */
    public record Args(String entity, String body) {
    }

    public static final ToolId ID = ToolId.of("minecraft", "entity.inspect");

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_READ;
    }


    @Override
    public String description() {
        return "Reads one entity by id: type, name, position, dimension, health, whether it is tame and whose, whether "
                + "it is a baby, and the item in its hand.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return EntityInspectResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("entity", BodyToolSchemas.text("Entity id, as body.scan reports it"));
        properties.add("body", BodyToolSchemas.body());
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<EntityInspectResult> invoke(ToolContext context, Args args) {
        if (args == null || args.entity() == null || args.entity().isBlank()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "entity.inspect needs an entity id"));
        }
        ActorScope scope = ActorScope.body(context.caller(), args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        EntityInspectRequest request = new EntityInspectRequest(args.entity());
        return TianshuRuntimePorts.find(EntityInspectPort.class)
                .map(port -> port.inspect(scope.actor(), request)
                        .thenCompose(result -> result.read()
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                                        result.message().isBlank() ? "the entity could not be read" : result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "entity inspection is only available on the server")));
    }
}