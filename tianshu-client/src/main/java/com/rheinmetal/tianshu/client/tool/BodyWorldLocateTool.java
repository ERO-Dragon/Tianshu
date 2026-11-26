package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.LocateResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Finds the nearest structure or biome of a given id, from where an agent body is.
 *
 * <p>Body side of the split pair; {@link WorldLocateTool} is the player side and owns the shared call in
 * {@link WorldLocateTool#locate}. The body's own position is the origin of the search, so the actor is not optional
 * here.</p>
 */
public final class BodyWorldLocateTool implements TianshuTool<BodyWorldLocateTool.Args, LocateResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.world.locate");

    public record Args(String body, String structure, String biome, Integer radius) {
    }

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
        return "Finds the nearest structure or biome of an id, from an agent body's position. Give exactly one of "
                + "structure/biome.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return LocateResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("structure", BodyToolSchemas.text(
                "Structure id to find, for example minecraft:village_plains"));
        properties.add("biome", BodyToolSchemas.text(
                "Biome id to find, for example minecraft:desert"));
        properties.add("radius", BodyToolSchemas.integer(
                "Search radius in blocks; defaults to 256, biomes sample at most 4096 positions"));
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<LocateResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        WorldLocateTool.Args locate = args == null
                ? null
                : new WorldLocateTool.Args(args.structure(), args.biome(), args.radius());
        return WorldLocateTool.locate(scope.actor(), locate);
    }
}