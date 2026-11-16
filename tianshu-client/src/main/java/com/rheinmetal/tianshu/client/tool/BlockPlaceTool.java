package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.BlockFace;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.BlockPlaceRequest;
import com.rheinmetal.tianshu.api.game.BlockPlaceResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Places one item from the actor's inventory into the world. */
public final class BlockPlaceTool implements TianshuTool<BlockPlaceTool.Args, BlockPlaceResult> {

    public static final ToolId ID = ToolId.of("tianshu", "block.place");

    /** {@code body} is optional; {@code face} is optional and lets the carrier aim from its own position. */
    public record Args(String body, String item, int x, int y, int z, String face) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_WRITE;
    }

    @Override
    public String description() {
        return "Places one item from the actor's inventory at a position, with vanilla orientation and consumption.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return BlockPlaceResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("item", BodyToolSchemas.text("Item id, for example minecraft:torch"));
        properties.add("x", BodyToolSchemas.coordinate("Block x"));
        properties.add("y", BodyToolSchemas.coordinate("Block y"));
        properties.add("z", BodyToolSchemas.coordinate("Block z"));
        JsonArray faces = new JsonArray();
        for (BlockFace face : BlockFace.values()) {
            faces.add(face.name().toLowerCase(Locale.ROOT));
        }
        JsonObject face = BodyToolSchemas.text("Side of the target block to place against; omit to aim from the body");
        face.add("enum", faces);
        properties.add("face", face);
        return BodyToolSchemas.object(properties, "item", "x", "y", "z");
    }

    @Override
    public CompletableFuture<BlockPlaceResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body(), ID);
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (args == null || args.item() == null || args.item().isBlank()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "block.place needs an item id"));
        }
        BlockFace face;
        try {
            face = args.face() == null || args.face().isBlank()
                    ? null
                    : BlockFace.valueOf(args.face().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownFace) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "unknown block face: " + args.face()));
        }
        Optional<ToolException> refusal = scope.capabilityRefusal(EntityCapability.PLACE);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        BlockPlaceRequest request = new BlockPlaceRequest(args.x(), args.y(), args.z(), args.item().trim(), face);
        return TianshuRuntimePorts.find(BlockPlacePort.class)
                .map(port -> port.place(scope.actor(), request).thenCompose(result ->
                        result.placed()
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.FAILED, result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "block placement is only available on the server")));
    }
}