package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Places a line of the same item from a body's inventory into the world.
 *
 * <p>There is no player-side twin on purpose: building a line of blocks by hand is yours to do, so the platform does
 * not offer it. The world operation lives in {@link PlaceLineOps}: this tool only adapts its arguments and calls in.
 * A body places with its own reach, its own drops and its own holding rules, which is why the actor is not optional
 * here.</p>
 */
public final class BodyPlaceLineTool implements
        TianshuTool<BodyPlaceLineTool.Args, PlaceLineOps.Placed> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.place.line");

    public record Args(String body, String item, int x, int y, int z, String direction, Integer count) {
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
        return "Makes an agent body place a line of the same item, starting at a position and stepping in a direction "
                + "(north, south, east, west, up, down), up to 16 blocks.";
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
        properties.add("body", BodyToolSchemas.body());
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
        properties.add("count", BodyToolSchemas.integer("How many blocks to place, 1..16"));
        return BodyToolSchemas.object(properties, "item", "x", "y", "z", "direction");
    }

    @Override
    public CompletableFuture<PlaceLineOps.Placed> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return PlaceLineOps.placeLine(scope.actor(), args.item(), args.x(), args.y(), args.z(), args.direction(),
                args.count());
    }
}