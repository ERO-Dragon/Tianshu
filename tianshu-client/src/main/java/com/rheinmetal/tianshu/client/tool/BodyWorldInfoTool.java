package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.WorldInfo;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * The world as it is around an agent body: dimension, biome, time, weather, difficulty, light.
 *
 * <p>Body side of the split pair; {@link WorldInfoTool} is the player side. The world operation itself lives in
 * {@link WorldInfoOps}: this tool only adapts its arguments and calls in. The body's own position is the origin, so the
 * actor is not optional here.</p>
 */
public final class BodyWorldInfoTool implements TianshuTool<BodyWorldInfoTool.Args, WorldInfo> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.world.info");

    public record Args(String body) {
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
        return "Reads the world around an agent body: dimension, biome, time, weather, difficulty, light level.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return WorldInfo.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<WorldInfo> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return WorldInfoOps.info(scope.actor());
    }
}