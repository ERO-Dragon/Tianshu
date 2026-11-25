package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Places a line of the same item, starting at a position and stepping in one direction.
 *
 * <p>A separate tool rather than a {@code count} on {@code block.place}: one placement answers with one result, and a
 * line answers with how far it got. Folding both into one result type would have made the single case carry a list it
 * never uses.</p>
 *
 * <p>The line stops at the first refusal — no item left, a block in the way, the carrier's own limits — and reports
 * what landed plus why it stopped, rather than failing everything because the last step failed.</p>
 */
public final class BlockPlaceLineTool implements TianshuTool<BlockPlaceLineTool.Args, BlockPlaceLineTool.Placed> {

    public static final ToolId ID = ToolId.of("minecraft", "block.place.line");

    /** A player-placed staircase is longer than this; anything bigger is a build job, not a tool call. */
    private static final int MAX_COUNT = 16;

    public record Args(String body, String item, int x, int y, int z, String direction, Integer count) {
    }

    /** What landed, and why the line stopped short of {@code requested}. */
    public record Placed(List<BlockPlaceResult> placed, int requested, String stoppedBecause) {
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
        return "Places a line of the same item, starting at a position and stepping in a direction (north, south, east, "
                + "west, up, down), up to 16 blocks. Stops at the first block that does not land and reports why.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return Placed.class;
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
        properties.add("count", BodyToolSchemas.integer("How many blocks to place, 1.." + MAX_COUNT));
        return BodyToolSchemas.object(properties, "item", "x", "y", "z", "direction");
    }

    @Override
    public CompletableFuture<Placed> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (args == null || args.item() == null || args.item().isBlank()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "block.placeLine needs an item id"));
        }
        int[] step = step(args.direction());
        if (step == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "direction must be north, south, east, west, up or down"));
        }
        int requested = args.count() == null ? 1 : args.count();
        if (requested < 1 || requested > MAX_COUNT) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "count must be 1.." + MAX_COUNT));
        }
        Optional<ToolException> refusal = scope.capabilityRefusal(EntityCapability.PLACE);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        BlockPlacePort port = TianshuRuntimePorts.find(BlockPlacePort.class).orElse(null);
        if (port == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "block placement is only available on the server"));
        }
        return placeFrom(port, scope.actor(), args.item().trim(), args.x(), args.y(), args.z(),
                step[0], step[1], step[2], requested, new ArrayList<>());
    }

    private static CompletableFuture<Placed> placeFrom(BlockPlacePort port,
                                                        com.rheinmetal.tianshu.api.entity.ActorRef actor,
                                                        String item, int x, int y, int z,
                                                        int dx, int dy, int dz, int requested,
                                                        List<BlockPlaceResult> done) {
        BlockPlaceRequest request = new BlockPlaceRequest(x, y, z, item, null);
        return port.place(actor, request).thenCompose(result -> {
            if (!result.placed()) {
                if (done.isEmpty()) {
                    return CompletableFuture.failedFuture(new ToolException(ToolError.FAILED, result.message()));
                }
                return CompletableFuture.completedFuture(
                        new Placed(List.copyOf(done), requested, result.message()));
            }
            done.add(result);
            if (done.size() >= requested) {
                return CompletableFuture.completedFuture(new Placed(List.copyOf(done), requested, ""));
            }
            return placeFrom(port, actor, item, x + dx, y + dy, z + dz, dx, dy, dz, requested, done);
        });
    }

    /** The unit step for a direction, or {@code null} when the name is not one of the six. */
    private static int[] step(String direction) {
        if (direction == null || direction.isBlank()) {
            return null;
        }
        return switch (direction.trim().toLowerCase(Locale.ROOT)) {
            case "north" -> new int[]{0, 0, -1};
            case "south" -> new int[]{0, 0, 1};
            case "east" -> new int[]{1, 0, 0};
            case "west" -> new int[]{-1, 0, 0};
            case "up" -> new int[]{0, 1, 0};
            case "down" -> new int[]{0, -1, 0};
            default -> null;
        };
    }
}