package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.MoveMode;
import com.rheinmetal.tianshu.api.agent.MoveOptions;
import com.rheinmetal.tianshu.api.agent.MoveResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 motion: both carriers are repositioned directly and turned in place.
 *
 * <p>Neither carrier walks or flies on its own, so {@code WALK} and {@code FLY} are refused rather than silently
 * treated as a teleport — the caller asked for a way of moving that this body does not have.</p>
 */
public final class AgentMotionSource implements AgentMotionPort {

    private final MinecraftServer server;
    private final BodyRegistry bodies;

    public AgentMotionSource(MinecraftServer server, BodyRegistry bodies) {
        this.server = server;
        this.bodies = bodies;
    }

    @Override
    public CompletableFuture<MoveResult> move(ActorRef actor, ActorPosition target, MoveOptions options) {
        CompletableFuture<MoveResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(moveNow(actor, target, options)));
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
        if (options.mode() == MoveMode.WALK || options.mode() == MoveMode.FLY) {
            return new MoveResult(ActionOutcome.REJECTED, here,
                    "this body moves by teleport, not by " + options.mode().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (!here.dimension().equals(target.dimension())) {
            return new MoveResult(ActionOutcome.REJECTED, here, "this body cannot change dimension");
        }
        body.entity().teleportTo(target.x(), target.y(), target.z());
        body.entity().setDeltaMovement(Vec3.ZERO);
        return new MoveResult(ActionOutcome.DONE, positionOf(body), "moved");
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