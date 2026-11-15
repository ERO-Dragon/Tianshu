package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBody;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.AgentWorldQueryPort;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.EntityHit;
import com.rheinmetal.tianshu.api.agent.FollowResult;
import com.rheinmetal.tianshu.api.agent.MoveMode;
import com.rheinmetal.tianshu.api.agent.MoveOptions;
import com.rheinmetal.tianshu.api.agent.ScanRequest;
import com.rheinmetal.tianshu.api.assembly.ServerTickPort;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Follows the nearest entity of one type until it is within reach, the target disappears, or the budget runs out.
 *
 * <p>A molecule: it re-scans and re-moves through the query and motion handlers, so it stops on its own instead of
 * holding a target forever. The caller's timeout is a step budget, not a wall clock.</p>
 */
public final class BodyFollowTool implements TianshuTool<BodyFollowTool.Args, FollowResult> {

    public static final ToolId ID = ToolId.of("tianshu", "body.follow");

    private static final int SCAN_RADIUS = 32;
    private static final int STEPS_PER_SECOND = 20;

    /** {@code entity} is an entity type id; {@code seconds} is the step budget. */
    public record Args(String body, String entity, double distance, int seconds) {
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
        return "Follows the nearest entity of one type until it is close, gone, or the time budget is spent.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return FollowResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("entity", BodyToolSchemas.text("Exact entity type id, for example minecraft:zombie"));
        properties.add("distance", BodyToolSchemas.number("Stop distance in blocks; defaults to 3"));
        properties.add("seconds", BodyToolSchemas.integer("Time budget in seconds; defaults to 30, capped at 300"));
        return BodyToolSchemas.object(properties, "entity");
    }

    @Override
    public CompletableFuture<FollowResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body(), ID);
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        Optional<ToolException> refusal = scope.capabilityRefusal(BodyCapability.MOVE);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        if (args == null || args.entity() == null || args.entity().isBlank()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.follow needs an entity type id"));
        }
        double stopDistance = args.distance() <= 0 ? 3.0D : args.distance();
        int seconds = args.seconds() <= 0 ? 30 : Math.min(args.seconds(), 300);
        Optional<ServerTickPort> ticks = TianshuRuntimePorts.find(ServerTickPort.class);
        if (ticks.isEmpty()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "following needs a server tick source"));
        }
        ActorRef actor = scope.actor();
        String entityId = args.entity().trim();
        CompletableFuture<FollowResult> result = new CompletableFuture<>();
        AtomicInteger steps = new AtomicInteger();
        int maxSteps = seconds * STEPS_PER_SECOND;
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        double[] lastDistance = {Double.NaN};
        Runnable step = new Runnable() {
            @Override
            public void run() {
                if (result.isDone()) {
                    return;
                }
                if (System.nanoTime() > deadlineNanos) {
                    // A wall clock, not only the step counter. One step waits on a move that can take seconds, so
                    // counting steps let the body keep walking long past the budget — and the platform's own timeout
                    // completed a *derived* future, so this one stayed pending: the slot stayed held and every later
                    // body.* call answered BUSY for five minutes. Completing this future here is what releases it.
                    finish(ActionOutcome.REJECTED, lastDistance[0],
                            "the follow used its " + seconds + "s budget");
                    return;
                }
                if (BodyOperations.cancelled(actor.id())) {
                    finish(ActionOutcome.REJECTED, lastDistance[0], "the call was cancelled");
                    return;
                }
                AgentBody body = AgentBodies.resolve(actor.id()).orElse(null);
                if (body == null) {
                    finish(ActionOutcome.REJECTED, lastDistance[0], "the agent body is not present");
                    return;
                }
                Optional<AgentWorldQueryPort> query = handler(actor, AgentBodyActions::query);
                Optional<AgentMotionPort> motion = handler(actor, AgentBodyActions::motion);
                if (query.isEmpty() || motion.isEmpty()) {
                    finish(ActionOutcome.REJECTED, lastDistance[0],
                            "this body cannot follow: it is missing a query or motion handler");
                    return;
                }
                if (steps.incrementAndGet() > maxSteps) {
                    finish(ActionOutcome.REJECTED, lastDistance[0], "the follow timed out");
                    return;
                }
                query.get().scan(actor, new ScanRequest(SCAN_RADIUS, 8, "", entityId))
                        .thenAccept(scan -> {
                            EntityHit target = nearest(scan.entities(), body.position());
                            if (target == null) {
                                finish(ActionOutcome.REJECTED, lastDistance[0], "no " + entityId + " nearby");
                                return;
                            }
                            double distance = Math.sqrt(distanceSquared(body.position(), target));
                            lastDistance[0] = distance;
                            if (distance <= stopDistance) {
                                finish(ActionOutcome.DONE, distance, "reached the target");
                                return;
                            }
                            motion.get().move(actor,
                                            new ActorPosition(target.dimension(), target.x(), target.y(), target.z()),
                                            new MoveOptions(MoveMode.ANY, 1.0D))
                                    .thenRun(() -> ticks.get().onNextTick(this))
                                    .exceptionally(error -> {
                                        fail(error);
                                        return null;
                                    });
                        })
                        .exceptionally(error -> {
                            fail(error);
                            return null;
                        });
            }

            private void finish(ActionOutcome outcome, double distance, String detail) {
                result.complete(new FollowResult(outcome, Double.isNaN(distance) ? -1.0D : distance, detail));
            }

            private void fail(Throwable failure) {
                finish(ActionOutcome.REJECTED, lastDistance[0],
                        failure.getMessage() == null ? "the follow failed" : failure.getMessage());
            }
        };
        return ActorScope.guarded(actor, context, () -> {
            ticks.get().onNextTick(step);
            return result;
        });
    }

    @Override
    public com.rheinmetal.tianshu.api.tool.ToolPolicy policy() {
        return com.rheinmetal.tianshu.api.tool.ToolPolicy.forCategory(category()).withTimeout(305_000L);
    }

    private static EntityHit nearest(List<EntityHit> hits, ActorPosition from) {
        EntityHit best = null;
        double bestDistance = Double.MAX_VALUE;
        for (EntityHit hit : hits) {
            double distance = distanceSquared(from, hit);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = hit;
            }
        }
        return best;
    }

    private static double distanceSquared(ActorPosition from, EntityHit hit) {
        double dx = hit.x() - from.x();
        double dy = hit.y() - from.y();
        double dz = hit.z() - from.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static <T> Optional<T> handler(ActorRef actor, Function<AgentBodyActions, Optional<T>> pick) {
        return AgentBodies.actions(actor.id()).flatMap(pick);
    }
}