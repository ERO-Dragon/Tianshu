package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.BlockInteractResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Operates a harmless block as an agent body that declares {@code USE}.
 *
 * <p>Body side of the split pair; {@link BlockInteractTool} is the player side and owns the shared call in
 * {@link BlockInteractTool#interact}.</p>
 */
public final class BodyInteractTool implements TianshuTool<BodyInteractTool.Args, BlockInteractResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.interact");

    public record Args(String body, int x, int y, int z) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.BODY_WRITE;
    }

    @Override
    public String description() {
        return "Makes an agent body press a button, toggle a lever or open a door/trapdoor/fence gate at the given "
                + "block position.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return BlockInteractResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("x", BodyToolSchemas.integer("Block x coordinate"));
        properties.add("y", BodyToolSchemas.integer("Block y coordinate"));
        properties.add("z", BodyToolSchemas.integer("Block z coordinate"));
        return BodyToolSchemas.object(properties, "x", "y", "z");
    }

    @Override
    public CompletableFuture<BlockInteractResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        BlockInteractTool.Args interact = args == null
                ? null
                : new BlockInteractTool.Args(args.x(), args.y(), args.z());
        return BlockInteractTool.interact(scope.actor(), interact);
    }
}