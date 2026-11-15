package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentActionPort;
import com.rheinmetal.tianshu.api.agent.BlockTarget;
import com.rheinmetal.tianshu.api.agent.EntityTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 world actions: break a block, strike an entity.
 *
 * <p>Both go through the carrier entity, so vanilla handles drops, protection hooks and events exactly as it would
 * for any other entity. Nothing here reaches for a player when the actor is a body.</p>
 */
public final class AgentActionSource implements AgentActionPort {

    private static final double REACH_SQUARED = 36.0;
    private static final float BASE_ATTACK_DAMAGE = 1.0F;

    private final MinecraftServer server;
    private final BodyRegistry bodies;

    public AgentActionSource(MinecraftServer server, BodyRegistry bodies) {
        this.server = server;
        this.bodies = bodies;
    }

    @Override
    public CompletableFuture<ActionResult> mine(ActorRef actor, BlockTarget target) {
        CompletableFuture<ActionResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(mineNow(actor, target)));
        return result;
    }

    @Override
    public CompletableFuture<ActionResult> attack(ActorRef actor, EntityTarget target) {
        CompletableFuture<ActionResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(attackNow(actor, target)));
        return result;
    }

    private ActionResult mineNow(ActorRef actor, BlockTarget target) {
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null) {
            return ActionResult.rejected("the agent body is not present");
        }
        String dimension = body.level().dimension().location().toString();
        if (!dimension.equals(target.dimension())) {
            return ActionResult.rejected("the target is in another dimension");
        }
        BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
        if (!body.level().hasChunkAt(pos)) {
            return ActionResult.rejected("that chunk is not loaded");
        }
        if (distanceSquared(body.position(), pos) > REACH_SQUARED) {
            return ActionResult.rejected("the block is out of reach");
        }
        BlockState state = body.level().getBlockState(pos);
        if (state.isAir()) {
            return new ActionResult(ActionOutcome.NO_EFFECT, "there is no block there");
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String blockId = id == null ? "" : id.toString();
        boolean broken = body.level().destroyBlock(pos, true, body.entity());
        return broken
                ? ActionResult.done("broke " + blockId)
                : new ActionResult(ActionOutcome.NO_EFFECT, "the block did not break");
    }

    private ActionResult attackNow(ActorRef actor, EntityTarget target) {
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null) {
            return ActionResult.rejected("the agent body is not present");
        }
        if (!(body.entity() instanceof LivingEntity attacker)) {
            return ActionResult.rejected("this body cannot attack");
        }
        UUID id = uuid(target.id());
        if (id == null) {
            return ActionResult.rejected("that is not an entity id");
        }
        if (id.equals(body.entity().getUUID())) {
            return ActionResult.rejected("a body cannot attack itself");
        }
        Entity victim = find(id);
        if (victim == null || victim.isRemoved()) {
            return ActionResult.rejected("the target entity is not present");
        }
        if (distanceSquared(body.position(), victim.position()) > REACH_SQUARED) {
            return ActionResult.rejected("the target is out of reach");
        }
        boolean struck = victim.hurt(victim.level().damageSources().mobAttack(attacker), BASE_ATTACK_DAMAGE);
        return struck
                ? ActionResult.done("struck " + id)
                : new ActionResult(ActionOutcome.NO_EFFECT, "the target was not affected");
    }

    private Entity find(UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static double distanceSquared(Vec3 from, BlockPos pos) {
        double dx = from.x - (pos.getX() + 0.5);
        double dy = from.y - (pos.getY() + 0.5);
        double dz = from.z - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceSquared(Vec3 from, Vec3 to) {
        return from.distanceToSqr(to);
    }
}