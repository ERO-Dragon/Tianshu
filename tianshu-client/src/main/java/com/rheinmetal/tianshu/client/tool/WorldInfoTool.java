package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.WorldInfo;
import com.rheinmetal.tianshu.api.game.WorldInfoPort;
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
 * The world as it is around you: dimension, biome, time, weather, difficulty, light.
 *
 * <p>Player side of the split pair; {@link BodyWorldInfoTool} is the body side and owns the shared call in
 * {@link #info(ActorRef, EmptyArgs)}.</p>
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
    public ToolPolicy policy() {
        return ToolPolicy.forCategory(ToolCategory.SERVER_READ).withEnabledByDefault(true);
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
        return info(scope.actor(), args);
    }

    /** Shared by both sides of the pair: the actor decides which position the world is read at. */
    static CompletableFuture<WorldInfo> info(ActorRef actor, EmptyArgs args) {
        return TianshuRuntimePorts.find(WorldInfoPort.class)
                .map(port -> port.info(actor)
                        .thenCompose(info -> info.read()
                                ? CompletableFuture.completedFuture(info)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.FAILED,
                                        info.message().isBlank() ? "the world could not be read" : info.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "world information is only available on the server")));
    }
}