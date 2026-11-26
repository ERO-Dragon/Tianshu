package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.BlockInspectResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Reads one block as an agent body: what it is, its state, and every storage it exposes.
 *
 * <p>Body side of the split pair; {@link BlockInspectTool} is the player side and owns the shared call in
 * {@link BlockInspectTool#inspect}. A body reads a block within its own reach, so the actor is not optional here.</p>
 */
public final class BodyBlockInspectTool implements TianshuTool<BodyBlockInspectTool.Args, BlockInspectResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.block.inspect");

    public record Args(String body, int x, int y, int z) {
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
        return "Makes an agent body read one block without opening it: its id, its state, and the items, fluids and "
                + "energy it holds.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return BlockInspectResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("x", BodyToolSchemas.integer("Block x coordinate"));
        properties.add("y", BodyToolSchemas.integer("Block y coordinate"));
        properties.add("z", BodyToolSchemas.integer("Block z coordinate"));
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<BlockInspectResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        BlockInspectTool.Args inspect = args == null
                ? null
                : new BlockInspectTool.Args(args.x(), args.y(), args.z());
        return BlockInspectTool.inspect(scope.actor(), inspect);
    }
}