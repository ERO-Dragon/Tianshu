package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.WorldInfo;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * The world as it is around you: dimension, biome, time, weather, difficulty, light.
 *
 * <p>Player side of the split pair; {@link BodyWorldInfoTool} is the body side. The world operation itself lives in
 * {@link WorldInfoOps}: this tool only adapts its arguments and calls in.</p>
 *
 * <p>Atomic: one read of the world at one position. It is deliberately not {@code world.time}, {@code world.weather}
 * and {@code world.biome} as three tools — those three answers are needed together ("is it night and dark and not
 * peaceful"), and three round trips would be three different moments.</p>
 */
public final class WorldInfoTool implements TianshuTool<EmptyArgs, WorldInfo> {

    public static final ToolId ID = ToolId.of("minecraft", "world.info");

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
        return "Reads the world around you: dimension, biome, time, weather, difficulty, light level.";
    }

    @Override
    public Type argsType() {
        return EmptyArgs.class;
    }

    @Override
    public Type resultType() {
        return WorldInfo.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<WorldInfo> invoke(ToolContext context, EmptyArgs args) {
        ActorScope scope = ActorScope.player(context.caller());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return WorldInfoOps.info(scope.actor());
    }
}