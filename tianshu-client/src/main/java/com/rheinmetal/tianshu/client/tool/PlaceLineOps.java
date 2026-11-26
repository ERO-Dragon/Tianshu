package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.BlockPlaceRequest;
import com.rheinmetal.tianshu.api.game.BlockPlaceResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Places a line of the same item, starting at a position and stepping in one direction.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides whether rights, reach and drops follow a player or a body.</p>
 *
 * <p>The line stops at the first refusal — no item left, a block in the way, the carrier's own limits — and reports
 * what landed plus why it stopped, rather than failing everything because the last step failed.</p>
 */
final class PlaceLineOps {

    /** A player-placed staircase is longer than this; anything bigger is a build job, not a tool call. */
    static final int MAX_COUNT = 16;

    /** What landed, and why the line stopped short of {@code requested}. */
    public record Placed(List<BlockPlaceResult> placed, int requested, String stoppedBecause) {
    }

    private PlaceLineOps() {
    }

    static CompletableFuture<Placed> placeLine(ActorRef actor, String item, int x, int y, int z,
                                               String direction, Integer count) {
        if (item == null || item.isBlank()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "block.placeLine needs an item id"));
        }
        int[] step = step(direction);
        if (step == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "direction must be north, south, east, west, up or down"));
        }
        int requested = count == null ? 1 : count;
        if (requested < 1 || requested > MAX_COUNT) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "count must be 1.." + MAX_COUNT));
        }
        Optional<ToolException> refusal = EntityActors.checkCapability(actor, EntityCapability.PLACE);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        BlockPlacePort port = TianshuRuntimePorts.find(BlockPlacePort.class).orElse(null);
        if (port == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "block placement is only available on the server"));
        }
        return placeFrom(port, actor, item.trim(), x, y, z,
                step[0], step[1], step[2], requested, new ArrayList<>());
    }

    private static CompletableFuture<Placed> placeFrom(BlockPlacePort port, ActorRef actor,
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
                return CompletableFuture.completedFuture(
                        new Placed(List.copyOf(done), requested, ""));
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