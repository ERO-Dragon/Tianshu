package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockBreakPort;
import com.rheinmetal.tianshu.api.game.BlockBreakRequest;
import com.rheinmetal.tianshu.api.game.BlockBreakResult;
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
 * Breaks one block as the acting body, or as the caller itself.
 *
 * <p>The counterpart of {@code block.place}: placing was open to a player and a body, breaking only existed for bodies
 * through {@code body.mine}. A module that acts for a player could build but not clear.</p>
 *
 * <p>A world change, so it is off by default like the other write tools; the actor's own rules still decide what it can
 * dig and what the block leaves behind.</p>
 */
public final class BlockBreakTool implements TianshuTool<BlockBreakTool.Args, BlockBreakResult> {

    /** {@code body} is optional: when present the break happens as that body, within its reach. */
    public record Args(int x, int y, int z, String body) {
    }

    public static final ToolId ID = ToolId.of("tianshu", "block.break");

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public com.rheinmetal.tianshu.api.tool.BodyAutonomy requiredBodyAutonomy() {
        // Survival work either way: a player placing a block and a carrier placing one are the same act.
        return com.rheinmetal.tianshu.api.tool.BodyAutonomy.SURVIVAL;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_WRITE;
    }

    @Override
    public ToolPolicy policy() {
        return ToolPolicy.forCategory(ToolCategory.SERVER_WRITE)
                .withPermission(com.rheinmetal.tianshu.api.tool.ToolPermission.EVERYONE);
    }

    @Override
    public String description() {
        return "Breaks one block at the given position, as you or as an agent body. What the block drops follows the "
                + "actor's own rules.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return BlockBreakResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("x", BodyToolSchemas.integer("Block x coordinate"));
        properties.add("y", BodyToolSchemas.integer("Block y coordinate"));
        properties.add("z", BodyToolSchemas.integer("Block z coordinate"));
        properties.add("body", BodyToolSchemas.body());
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<BlockBreakResult> invoke(ToolContext context, Args args) {
        if (args == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "block.break needs a position"));
        }
        ActorScope scope = ActorScope.of(context.caller(), args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        BlockBreakRequest request = new BlockBreakRequest(args.x(), args.y(), args.z());
        return TianshuRuntimePorts.find(BlockBreakPort.class)
                .map(port -> port.breakBlock(scope.actor(), request)
                        .thenCompose(result -> result.broken()
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.FAILED,
                                        result.message().isBlank() ? "the block was not broken" : result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "breaking is only available on the server")));
    }
}