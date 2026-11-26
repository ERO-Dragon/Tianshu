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
 * Breaks one block as the calling player.
 *
 * <p>The counterpart of {@code block.place}: placing is a player action and a body action, so breaking is offered on
 * both sides too. The body side already exists as {@code entity.mine} ({@link BodyMineTool}); this is the player side.</p>
 *
 * <p>A world change, so it is off by default like the other write tools; the player's own rights still decide what they
 * can dig and what the block leaves behind.</p>
 */
public final class BlockBreakTool implements TianshuTool<BlockBreakTool.Args, BlockBreakResult> {

    public record Args(int x, int y, int z) {
    }

    public static final ToolId ID = ToolId.of("minecraft", "block.break");

    @Override
    public ToolId id() {
        return ID;
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
        return "Breaks one block at the given position. What the block drops follows your own rules.";
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
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<BlockBreakResult> invoke(ToolContext context, Args args) {
        if (args == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "block.break needs a position"));
        }
        ActorScope scope = ActorScope.player(context.caller());
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