package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.LocateResult;
import com.rheinmetal.tianshu.api.game.LocateRequest;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Finds the nearest structure or biome of a given id, from where an agent body is.
 *
 * <p>Body side of the split pair; {@link WorldLocateTool} is the player side. The world operation itself lives in
 * {@link WorldLocateOps}: this tool only adapts its arguments and calls in. The body's own position is the origin of the
 * search, so the actor is not optional here.</p>
 */
public final class BodyWorldLocateTool implements TianshuTool<BodyWorldLocateTool.Args, LocateResult> {

    public static final ToolId LOCATE = ToolId.of("minecraft", "entity.world.locate");
    public static final ToolId SURVEY = ToolId.of("minecraft", "entity.world.survey");
    private final LocateRequest.Scope searchScope;

    public BodyWorldLocateTool(LocateRequest.Scope searchScope) {
        this.searchScope = java.util.Objects.requireNonNull(searchScope);
    }

    public record Args(String body, String structure, String biome, Integer radius) {
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
        return "Locate a structure or sample a biome from the NPC position. "
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
        properties.add("body", BodyToolSchemas.body());
        WorldLocateOps.describeSearch(properties);
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<LocateResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return WorldLocateOps.locate(context.cancellation(), scope.actor(), searchScope, args.structure(), args.biome(), args.radius());
    }
}