package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.BlockPlaceResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Places a line of the same item, starting at a position and stepping in one direction.
 *
 * <p>A separate tool rather than a {@code count} on {@code block.place}: one placement answers with one result, and a
 * line answers with how far it got. Folding both into one result type would have made the single case carry a list it
 * never uses.</p>
 *
 * <p>Player side of the split pair; {@link BodyPlaceLineTool} is the body side. The world operation itself lives in
 * {@link PlaceLineOps}: this tool only adapts its arguments and calls in, so the two sides differ only in who they act
 * as.</p>
 *
 * <p>The line stops at the first refusal — no item left, a block in the way, the carrier's own limits — and reports
 * what landed plus why it stopped, rather than failing everything because the last step failed.</p>
 */
public final class BlockPlaceLineTool implements TianshuTool<BlockPlaceLineTool.Args, PlaceLineOps.Placed> {

    public static final ToolId ID = ToolId.of("minecraft", "block.place.line");

    public record Args(String item, int x, int y, int z, String direction, Integer count) {
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
    public ToolPolicy policy() {
        // Any survival player may do this by hand; the server switch and their own rights still bound it.
        return ToolPolicy.forCategory(ToolCategory.SERVER_WRITE)
                .withPermission(ToolPermission.EVERYONE)
                .withEnabledByDefault(true);
    }

    @Override
    public String description() {
        return "Places a line of the same item, starting at a position and stepping in a direction (north, south, east, "
                + "west, up, down), up to 16 blocks. Stops at the first block that does not land and reports why.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return PlaceLineOps.Placed.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("item", BodyToolSchemas.text("Item id, for example minecraft:stone"));
        properties.add("x", BodyToolSchemas.coordinate("First block x"));
        properties.add("y", BodyToolSchemas.coordinate("First block y"));
        properties.add("z", BodyToolSchemas.coordinate("First block z"));
        JsonArray directions = new JsonArray();
        for (String direction : List.of("north", "south", "east", "west", "up", "down")) {
            directions.add(direction);
        }
        JsonObject direction = BodyToolSchemas.text("Direction the line grows in");
        direction.add("enum", directions);
        properties.add("direction", direction);
        properties.add("count", BodyToolSchemas.integer("How many blocks to place, 1.." + PlaceLineOps.MAX_COUNT));
        return BodyToolSchemas.object(properties, "item", "x", "y", "z", "direction");
    }

    @Override
    public CompletableFuture<PlaceLineOps.Placed> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.player(context.caller());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return PlaceLineOps.placeLine(scope.actor(), args.item(), args.x(), args.y(), args.z(), args.direction(),
                args.count());
    }
}