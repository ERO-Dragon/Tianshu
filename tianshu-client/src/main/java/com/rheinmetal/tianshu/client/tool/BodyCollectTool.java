package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentActionPort;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBody;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.AgentItemPort;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.AgentWorldQueryPort;
import com.rheinmetal.tianshu.api.agent.BlockHit;
import com.rheinmetal.tianshu.api.agent.BlockTarget;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.CollectResult;
import com.rheinmetal.tianshu.api.agent.MoveMode;
import com.rheinmetal.tianshu.api.agent.MoveOptions;
import com.rheinmetal.tianshu.api.agent.ScanRequest;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Collects blocks of one id: find the nearest, go there, mine it, pick up the drops, repeat until the count is met
 * or no candidate is left.
 *
 * <p>A molecule, not a new world operation: it composes the query, motion, action and item handlers, so any body
 * that offers those handlers can collect — including a third-party carrier.</p>
 */
public final class BodyCollectTool implements TianshuTool<BodyCollectTool.Args, CollectResult> {

    public static final ToolId ID = ToolId.of("tianshu", "body.collect");

    private static final int MAX_COUNT = 64;
    private static final int MAX_RADIUS = 16;
    private static final int DEFAULT_RADIUS = 8;

    /** {@code mode} selects how to reach the block: any/walk/teleport. */
    public record Args(String body, String block, int count, int radius, String mode) {
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
        return "Collects blocks of one id: goes to the nearest, mines it and picks up the drops, up to a count.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return CollectResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("block", BodyToolSchemas.text("Exact block id, for example minecraft:stone"));
        properties.add("count", BodyToolSchemas.integer("How many blocks; defaults to 1, capped at 64"));
        properties.add("radius", BodyToolSchemas.integer("Search radius; defaults to 8, capped at 16"));
        properties.add("mode", BodyToolSchemas.text("How to reach the block: any, walk or teleport"));
        return BodyToolSchemas.object(properties, "block");
    }

    @Override
    public CompletableFuture<CollectResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body(), ID);
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        for (BodyCapability capability : List.of(BodyCapability.MOVE, BodyCapability.MINE,
                BodyCapability.INVENTORY)) {
            Optional<ToolException> refusal = scope.capabilityRefusal(capability);
            if (refusal.isPresent()) {
                return CompletableFuture.failedFuture(refusal.get());
            }
        }
        if (args == null || args.block() == null || args.block().isBlank()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.collect needs a block id"));
        }
        MoveMode mode;
        try {
            mode = args.mode() == null || args.mode().isBlank()
                    ? MoveMode.ANY
                    : MoveMode.valueOf(args.mode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownMode) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "mode must be any, walk, fly or teleport"));
        }
        int count = args.count() <= 0 ? 1 : Math.min(args.count(), MAX_COUNT);
        int radius = args.radius() <= 0 ? DEFAULT_RADIUS : Math.min(args.radius(), MAX_RADIUS);
        ActorRef actor = scope.actor();
        return run(actor, args.block().trim(), count, 0, mode, radius);
    }

    private CompletableFuture<CollectResult> run(ActorRef actor, String blockId, int wanted, int collected,
                                                 MoveMode mode, int radius) {
        if (collected >= wanted) {
            return CompletableFuture.completedFuture(
                    new CollectResult(collected, 0, "collected " + collected + " " + blockId));
        }
        AgentBody body = AgentBodies.resolve(actor.id()).orElse(null);
        if (body == null) {
            return CompletableFuture.completedFuture(
                    new CollectResult(collected, wanted - collected, "the agent body is not present"));
        }
        Optional<AgentWorldQueryPort> query = handler(actor, AgentBodyActions::query);
        Optional<AgentMotionPort> motion = handler(actor, AgentBodyActions::motion);
        Optional<AgentActionPort> action = handler(actor, AgentBodyActions::action);
        Optional<AgentItemPort> items = handler(actor, AgentBodyActions::items);
        if (query.isEmpty() || motion.isEmpty() || action.isEmpty()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "this body cannot collect: it is missing a query, motion or action handler"));
        }
        int remaining = wanted - collected;
        return query.get().scan(actor, new ScanRequest(radius, 40, blockId, ""))
                .thenCompose(scan -> {
                    BlockHit nearest = nearest(scan.blocks(), body.position());
                    if (nearest == null) {
                        return CompletableFuture.completedFuture(new CollectResult(collected, remaining,
                                "no " + blockId + " within " + radius + " blocks"));
                    }
                    ActorPosition above = new ActorPosition(nearest.dimension(), nearest.x() + 0.5D,
                            nearest.y() + 1.0D, nearest.z() + 0.5D);
                    return motion.get().move(actor, above, new MoveOptions(mode, 2.0D))
                            .thenCompose(move -> {
                                if (move.outcome() != ActionOutcome.DONE) {
                                    return CompletableFuture.completedFuture(new CollectResult(collected,
                                            remaining, "could not reach the block: " + move.detail()));
                                }
                                return action.get().mine(actor, new BlockTarget(nearest.dimension(),
                                                nearest.x(), nearest.y(), nearest.z()))
                                        .thenCompose(mine -> {
                                            if (mine.outcome() != ActionOutcome.DONE) {
                                                return CompletableFuture.completedFuture(new CollectResult(
                                                        collected, remaining,
                                                        "could not mine the block: " + mine.detail()));
                                            }
                                            if (items.isEmpty()) {
                                                return CompletableFuture.completedFuture(new CollectResult(
                                                        collected + 1, remaining - 1,
                                                        "mined " + blockId + " but this body has no item handler"));
                                            }
                                            return items.get().pickup(actor, 3)
                                                    .thenCompose(pickup -> run(actor, blockId, wanted,
                                                            collected + 1, mode, radius));
                                        });
                            });
                });
    }

    private static BlockHit nearest(List<BlockHit> hits, ActorPosition from) {
        BlockHit best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockHit hit : hits) {
            double dx = hit.x() + 0.5D - from.x();
            double dy = hit.y() + 0.5D - from.y();
            double dz = hit.z() + 0.5D - from.z();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = hit;
            }
        }
        return best;
    }

    private static <T> Optional<T> handler(ActorRef actor, Function<AgentBodyActions, Optional<T>> pick) {
        return AgentBodies.actions(actor.id()).flatMap(pick);
    }
}