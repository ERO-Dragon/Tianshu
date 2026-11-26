package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.LocateResult;
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
 * Finds the nearest structure or biome of a given id, from where you are.
 *
 * <p>Player side of the split pair; {@link BodyWorldLocateTool} is the body side. The world operation itself lives in
 * {@link WorldLocateOps}: this tool only adapts its arguments and calls in.</p>
 *
 * <p>Atomic: it answers "where is it", not "go there" — {@code body.move} does the walking and the caller decides whether
 * going is worth it.</p>
 *
 * <p>Exactly one of {@code structure} and {@code biome} is required. The two searches differ in what a negative answer
 * means, and the result carries {@code sampled} so a biome "not found" is not mistaken for "there is none": structures
 * are searched exactly, biomes are sampled on a grid.</p>
 */
public final class WorldLocateTool implements TianshuTool<WorldLocateTool.Args, LocateResult> {

    public record Args(String structure, String biome, Integer radius) {
    }

    public static final ToolId ID = ToolId.of("minecraft", "world.locate");

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
        return "Finds the nearest structure or biome of an id. Give exactly one of structure/biome. Structures are "
                + "searched exactly; biomes are sampled on a 16-block grid, so their 'not found' means not in the "
                + "sample and the result reports how many positions were looked at.";
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
        if (args == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "world.locate needs a structure id or a biome id"));
        }
        ActorScope scope = ActorScope.player(context.caller());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return WorldLocateOps.locate(scope.actor(), args.structure(), args.biome(), args.radius());
    }
}