package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.BlockFace;
import com.rheinmetal.tianshu.api.game.BlockPlaceResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Places one item from a body's inventory into the world.
 *
 * <p>Body side of the split pair; {@link BlockPlaceTool} is the player side and owns
 * {@link BlockPlaceTool#place}. A carrier places with its own reach, its own drops and its own holding rules, which is
 * why the actor is not optional here.</p>
 */
public final class BodyPlaceTool implements TianshuTool<BodyPlaceTool.Args, BlockPlaceResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.place");

    /** {@code body} is filled in when the caller is that body; {@code face} is optional. */
    public record Args(String body, String item, int x, int y, int z, String face) {
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
        return "Makes an agent body place one item from its own inventory at a position.";
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
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        BlockPlaceTool.Args placed = args == null
                ? null
                : new BlockPlaceTool.Args(args.item(), args.x(), args.y(), args.z(), args.face());
        return BlockPlaceTool.place(scope.actor(), placed);
    }
}