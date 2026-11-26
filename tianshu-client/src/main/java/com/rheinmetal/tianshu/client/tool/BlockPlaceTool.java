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
 * Places one item from the calling player's inventory into the world.
 *
 * <p>Player side of the split pair; {@link BodyPlaceTool} is the body side. The world operation itself lives in
 * {@link PlaceOps}: this tool only adapts its arguments and calls in, so the two sides differ only in who they act
 * as.</p>
 */
public final class BlockPlaceTool implements TianshuTool<BlockPlaceTool.Args, BlockPlaceResult> {

    public static final ToolId ID = ToolId.of("minecraft", "block.place");

    /** {@code face} is optional and lets the actor aim from its own position. */
    public record Args(String item, int x, int y, int z, String face) {
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
    public com.rheinmetal.tianshu.api.tool.ToolPolicy policy() {
        // Any survival player may do this by hand; the server switch and their own rights still bound it.
        return com.rheinmetal.tianshu.api.tool.ToolPolicy.forCategory(ToolCategory.SERVER_WRITE)
                .withPermission(com.rheinmetal.tianshu.api.tool.ToolPermission.EVERYONE)
                .withEnabledByDefault(true);
    }

    @Override
    public String description() {
        return "Places one item from your inventory at a position, with vanilla orientation and consumption.";
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
        properties.add("item", BodyToolSchemas.text("Item id, for example minecraft:torch"));
        properties.add("x", BodyToolSchemas.coordinate("Block x"));
        properties.add("y", BodyToolSchemas.coordinate("Block y"));
        properties.add("z", BodyToolSchemas.coordinate("Block z"));
        JsonArray faces = new JsonArray();
        for (BlockFace face : BlockFace.values()) {
            faces.add(face.name().toLowerCase(Locale.ROOT));
        }
        JsonObject face = BodyToolSchemas.text("Side of the target block to place against; omit to aim from where you stand");
        face.add("enum", faces);
        properties.add("face", face);
        return BodyToolSchemas.object(properties, "item", "x", "y", "z");
    }

    @Override
    public CompletableFuture<BlockPlaceResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.player(context.caller());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return PlaceOps.place(scope.actor(), args.item(), args.x(), args.y(), args.z(), args.face());
    }
}