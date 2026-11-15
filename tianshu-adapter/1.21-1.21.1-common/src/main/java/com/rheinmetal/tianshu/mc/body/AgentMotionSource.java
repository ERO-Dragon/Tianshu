package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.MoveMode;
import com.rheinmetal.tianshu.api.agent.MoveOptions;
import com.rheinmetal.tianshu.api.agent.MoveResult;
import com.rheinmetal.tianshu.api.assembly.ServerTickPort;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.client.tool.BodyOperations;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The 1.21.1 motion: teleport and turn for every carrier, plus real walking for the {@code WALKER} carrier.
 *
 * <p>Walking uses vanilla navigation and finishes through the server tick port: the future completes when the body
 * arrives, when the path ends short, or when the walk times out — never with a "started" that pretends to be
 * "arrived".</p>
 */
public final class AgentMotionSource implements AgentMotionPort {

    private static final int WALK_TIMEOUT_TICKS = 600;
    /** Same threshold Promaid uses for "she has not moved for 40 ticks": stuck, not slowly progressing. */
    private static final int STALL_TICKS = 40;

    private final MinecraftServer server;
    private final BodyRegistry bodies;

    public AgentMotionSource(MinecraftServer server, BodyRegistry bodies) {
        this.server = server;
        this.bodies = bodies;
    }

    @Override
    public CompletableFuture<MoveResult> move(ActorRef actor, ActorPosition target, MoveOptions options) {
        CompletableFuture<MoveResult> result = new CompletableFuture<>();
        server.execute(() -> {
            if (options.mode() == MoveMode.WALK) {
                startWalk(actor, target, options, result);
            } else {
                result.complete(moveNow(actor, target, options));
            }
        });
        return result;
    }

    @Override
    public CompletableFuture<ActionResult> look(ActorRef actor, ActorPosition target) {
        CompletableFuture<ActionResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(lookNow(actor, target)));
        return result;
    }

    private MoveResult moveNow(ActorRef actor, ActorPosition target, MoveOptions options) {
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null) {
            return new MoveResult(ActionOutcome.REJECTED, current(actor), "the agent body is not present");
        }
        ActorPosition here = positionOf(body);
        if (options.mode() == MoveMode.FLY) {
            return new MoveResult(ActionOutcome.REJECTED, here, "this body cannot fly");
        }
        if (!here.dimension().equals(target.dimension())) {
            return new MoveResult(ActionOutcome.REJECTED, here, "this body cannot change dimension");
        }
        body.entity().teleportTo(target.x(), target.y(), target.z());
        body.entity().setDeltaMovement(Vec3.ZERO);
        return new MoveResult(ActionOutcome.DONE, positionOf(body), "moved");
    }

    /** Starts vanilla navigation and completes the move when the body arrives, runs out of path or times out. */
    private void startWalk(ActorRef actor, ActorPosition target, MoveOptions options,
                           CompletableFuture<MoveResult> result) {
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null) {
            result.complete(new MoveResult(ActionOutcome.REJECTED, current(actor), "the agent body is not present"));
            return;
        }
        ActorPosition here = positionOf(body);
        if (!here.dimension().equals(target.dimension())) {
            result.complete(new MoveResult(ActionOutcome.REJECTED, here, "this body cannot change dimension"));
            return;
        }
        if (!(body.entity() instanceof PathfinderMob walker)) {
            result.complete(new MoveResult(ActionOutcome.REJECTED, here,
                    "this body cannot walk; it only teleports"));
            return;
        }
        ServerTickPort ticks = TianshuRuntimePorts.find(ServerTickPort.class).orElse(null);
        if (ticks == null) {
            result.complete(new MoveResult(ActionOutcome.REJECTED, here,
                    "the server does not offer a tick source"));
            return;
        }
        PathNavigation navigation = walker.getNavigation();
        if (!navigation.moveTo(target.x(), target.y(), target.z(), 1.0D)) {
            result.complete(new MoveResult(ActionOutcome.REJECTED, here, "no path to the target"));
            return;
        }
        double arriveRadius = Math.max(0.5D, options.arriveRadius());
        double arriveSquared = arriveRadius * arriveRadius;
        AtomicInteger elapsed = new AtomicInteger();
        double[] anchorX = {walker.getX()};
        double[] anchorY = {walker.getY()};
        double[] anchorZ = {walker.getZ()};
        AtomicInteger idle = new AtomicInteger();
        Runnable step = new Runnable() {
            @Override
            public void run() {
                if (result.isDone()) {
                    return;
                }
                if (com.rheinmetal.tianshu.client.tool.BodyOperations.cancelled(actor.id())) {
                    navigation.stop();
                    result.completeExceptionally(new com.rheinmetal.tianshu.api.tool.ToolException(
                            com.rheinmetal.tianshu.api.tool.ToolError.CANCELLED, "the call was cancelled"));
                    return;
                }
                if (walker.isRemoved()) {
                    finish(ActionOutcome.REJECTED, "the body disappeared");
                    return;
                }
                double movedX = walker.getX() - anchorX[0];
                double movedY = walker.getY() - anchorY[0];
                double movedZ = walker.getZ() - anchorZ[0];
                if (movedX * movedX + movedY * movedY + movedZ * movedZ > 0.0025D) {
                    anchorX[0] = walker.getX();
                    anchorY[0] = walker.getY();
                    anchorZ[0] = walker.getZ();
                    idle.set(0);
                } else if (idle.incrementAndGet() >= STALL_TICKS) {
                    navigation.stop();
                    finish(ActionOutcome.REJECTED, "the walk stalled");
                    return;
                }
                if (walker.position().distanceToSqr(target.x(), target.y(), target.z()) <= arriveSquared) {
                    navigation.stop();
                    finish(ActionOutcome.DONE, "walked");
                    return;
                }
                if (navigation.isDone()) {
                    finish(ActionOutcome.REJECTED, "no path to the target");
                    return;
                }
                if (elapsed.incrementAndGet() >= WALK_TIMEOUT_TICKS) {
                    navigation.stop();
                    finish(ActionOutcome.REJECTED, "the walk timed out");
                    return;
                }
                ticks.onNextTick(this);
            }

            private void finish(ActionOutcome outcome, String detail) {
                result.complete(new MoveResult(outcome, positionOf(body), detail));
            }
        };
        ticks.onNextTick(step);
    }

    private ActionResult lookNow(ActorRef actor, ActorPosition target) {
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null) {
            return ActionResult.rejected("the agent body is not present");
        }
        if (!positionOf(body).dimension().equals(target.dimension())) {
            return ActionResult.rejected("the target is in another dimension");
        }
        Vec3 eye = body.entity().getEyePosition();
        double dx = target.x() - eye.x;
        double dy = target.y() - eye.y;
        double dz = target.z() - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, horizontal) * (180.0 / Math.PI));
        body.entity().setYRot(yaw);
        body.entity().setXRot(pitch);
        if (body.entity() instanceof LivingEntity living) {
            living.setYHeadRot(yaw);
        }
        return ActionResult.done("facing " + Math.round(target.x()) + " " + Math.round(target.y()) + " "
                + Math.round(target.z()));
    }

    private ActorPosition current(ActorRef actor) {
        return bodies.state(actor.id())
                .map(AgentMotionSource::positionOf)
                .orElse(new ActorPosition("minecraft:overworld", 0, 0, 0));
    }

    private static ActorPosition positionOf(BodyRegistry.BodyState body) {
        Vec3 at = body.position();
        return new ActorPosition(body.level().dimension().location().toString(), at.x, at.y, at.z);
    }
}
