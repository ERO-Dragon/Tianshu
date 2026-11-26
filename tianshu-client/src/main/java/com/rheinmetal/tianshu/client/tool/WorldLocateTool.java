package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.LocateResult;
import com.rheinmetal.tianshu.api.game.LocateRequest;
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
 * <p>Atomic: it answers "where is it", not "go there" — {@code entity.move} does the walking and the caller decides whether
 * going is worth it.</p>
 *
 * <p>Exactly one of {@code structure} and {@code biome} is required. The two searches differ in what a negative answer
 * means, and the result carries {@code sampled} so a biome "not found" is not mistaken for "there is none": structures
 * are searched exactly, biomes are sampled on a grid.</p>
 */
public final class WorldLocateTool implements TianshuTool<WorldLocateTool.Args, LocateResult> {

    public record Args(String structure, String biome, Integer radius) {
    }

    public static final ToolId LOCATE = ToolId.of("minecraft", "world.locate");
    public static final ToolId SURVEY = ToolId.of("minecraft", "world.survey");
    private final LocateRequest.Scope searchScope;

    public WorldLocateTool(LocateRequest.Scope searchScope) {
        this.searchScope = java.util.Objects.requireNonNull(searchScope);
    }

    public static java.util.List<TianshuTool<?, ?>> tools() {
        return java.util.List.of(new WorldLocateTool(LocateRequest.Scope.GENERATED),
                new WorldLocateTool(LocateRequest.Scope.WORLD),
                new BodyWorldLocateTool(LocateRequest.Scope.GENERATED),
                new BodyWorldLocateTool(LocateRequest.Scope.WORLD));
    }

    @Override
    public ToolId id() {
        return searchScope == LocateRequest.Scope.GENERATED ? LOCATE : SURVEY;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_READ;
    }

    @Override
    public ToolPolicy policy() {
        return WorldLocateOps.POLICY;
    }

    @Override
    public String description() {
        return "Locate a structure or sample a biome from the player position. "
                + WorldLocateOps.describeScope(searchScope);
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
        WorldLocateOps.describeSearch(properties);
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
        return WorldLocateOps.locate(context.cancellation(), scope.actor(), searchScope, args.structure(), args.biome(), args.radius());
    }
}