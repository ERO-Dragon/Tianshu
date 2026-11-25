package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockInspectPort;
import com.rheinmetal.tianshu.api.game.BlockInspectRequest;
import com.rheinmetal.tianshu.api.game.BlockInspectResult;
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
 * Reads one block: what it is, its state, and every storage it exposes.
 *
 * <p>Read-only and on by default: knowing what is in a chest or a machine is perception, not a world change. It does
 * not need the player to have anything open — that is the difference from {@code container.inspect}, which reads the
 * menu the player already has, and the reason this one works for machines whose screen the platform cannot render.</p>
 *
 * <p>A block that exposes no storage still answers: its id, its state and its block entity type are the answer, and
 * "nothing here" is a useful thing to know.</p>
 */
public final class BlockInspectTool implements TianshuTool<BlockInspectTool.Args, BlockInspectResult> {

    /** {@code body} is optional: when present the block is read as that body, within its reach. */
    public record Args(int x, int y, int z, String body) {
    }

    public static final ToolId ID = ToolId.of("minecraft", "block.inspect");

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
        return "Reads one block without opening it: its id, its state, and the items, fluids and energy it holds.";
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
        properties.add("x", BodyToolSchemas.integer("Block x coordinate"));
        properties.add("y", BodyToolSchemas.integer("Block y coordinate"));
        properties.add("z", BodyToolSchemas.integer("Block z coordinate"));
        properties.add("body", BodyToolSchemas.body());
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<BlockInspectResult> invoke(ToolContext context, Args args) {
        if (args == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "block.inspect needs a position"));
        }
        ActorScope scope = ActorScope.of(context.caller(), args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        BlockInspectRequest request = new BlockInspectRequest(args.x(), args.y(), args.z());
        return TianshuRuntimePorts.find(BlockInspectPort.class)
                .map(port -> port.inspect(scope.actor(), request)
                        .thenCompose(result -> result.inspected()
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.FAILED,
                                        result.message().isBlank() ? "nothing could be read there" : result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "block inspection is only available on the server")));
    }
}