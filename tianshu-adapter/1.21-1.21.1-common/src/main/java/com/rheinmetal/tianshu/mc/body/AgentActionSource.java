package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentActionPort;
import com.rheinmetal.tianshu.api.agent.BlockTarget;
import com.rheinmetal.tianshu.api.agent.EntityTarget;
import com.rheinmetal.tianshu.api.assembly.ServerTickPort;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The 1.21.1 world actions: break a block, strike an entity.
 *
 * <p>Breaking is progressive, like a player: each server tick adds the block's destroy progress for the equipped
 * item, the tool wears as it is used, and the call completes only when the block actually breaks — or when the block
 * changes, the body moves out of reach, or the mine times out. Striking stays single-hit; an equipped item adds its
 * attack damage and wears one point per successful strike.</p>
 */
public final class AgentActionSource implements AgentActionPort {

    private static final double REACH_SQUARED = 36.0;
    private static final float BASE_ATTACK_DAMAGE = 1.0F;
    private static final int MINE_TIMEOUT_TICKS = 1200;

    private final MinecraftServer server;
    private final BodyRegistry bodies;
    private final ActionPlayerFactory actionPlayers;

    public AgentActionSource(MinecraftServer server, BodyRegistry bodies, ActionPlayerFactory actionPlayers) {
        this.server = server;
        this.bodies = bodies;
        this.actionPlayers = actionPlayers;
    }

    @Override
    public CompletableFuture<ActionResult> mine(ActorRef actor, BlockTarget target) {
        CompletableFuture<ActionResult> result = new CompletableFuture<>();
        server.execute(() -> startMine(actor, target, result));
        return result;
    }

    @Override
    public CompletableFuture<ActionResult> attack(ActorRef actor, EntityTarget target) {
        CompletableFuture<ActionResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(attackNow(actor, target)));
        return result;
    }

    /** Starts a progressive mine; completes when the block breaks, changes, goes out of reach or times out. */
    private void startMine(ActorRef actor, BlockTarget target, CompletableFuture<ActionResult> result) {
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null) {
            result.complete(ActionResult.rejected("the agent body is not present"));
            return;
        }
        String dimension = body.level().dimension().location().toString();
        if (!dimension.equals(target.dimension())) {
            result.complete(ActionResult.rejected("the target is in another dimension"));
            return;
        }
        BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
        if (!body.level().hasChunkAt(pos)) {
            result.complete(ActionResult.rejected("that chunk is not loaded"));
            return;
        }
        if (distanceSquared(body.position(), pos) > REACH_SQUARED) {
            result.complete(ActionResult.rejected("the block is out of reach"));
            return;
        }
        BlockState initial = body.level().getBlockState(pos);
        if (initial.isAir()) {
            result.complete(new ActionResult(ActionOutcome.NO_EFFECT, "there is no block there"));
            return;
        }
        ServerTickPort ticks = TianshuRuntimePorts.find(ServerTickPort.class).orElse(null);
        if (ticks == null) {
            result.complete(ActionResult.rejected("the server does not offer a tick source"));
            return;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(initial.getBlock());
        String blockId = id == null ? "" : id.toString();
        int bodySlot = bodies.activeSlot(body.id().toString());
        ActionContext context = ActionContext.hand(server, actor, bodies, actionPlayers, bodySlot).orElse(null);
        if (context == null) {
            result.complete(ActionResult.rejected("the agent body is not present"));
            return;
        }
        Player player = context.player();
        if (initial.getDestroyProgress(player, body.level(), pos) <= 0.0F) {
            context.close();
            result.complete(ActionResult.rejected("this body cannot break that block"));
            return;
        }
        double[] progress = {0.0D};
        AtomicInteger elapsed = new AtomicInteger();
        Runnable step = new Runnable() {
            @Override
            public void run() {
                if (result.isDone()) {
                    return;
                }
                if (com.rheinmetal.tianshu.client.tool.BodyOperations.cancelled(actor.id())) {
                    finish(ActionOutcome.REJECTED, "the call was cancelled");
                    return;
                }
                if (body.entity().isRemoved()) {
                    finish(ActionOutcome.REJECTED, "the body disappeared");
                    return;
                }
                BlockState current = body.level().getBlockState(pos);
                if (current != initial) {
                    finish(ActionOutcome.REJECTED, "the block changed");
                    return;
                }
                if (distanceSquared(body.position(), pos) > REACH_SQUARED) {
                    finish(ActionOutcome.REJECTED, "the block is out of reach");
                    return;
                }
                if (elapsed.incrementAndGet() >= MINE_TIMEOUT_TICKS) {
                    finish(ActionOutcome.REJECTED, "mining timed out");
                    return;
                }
                float added = current.getDestroyProgress(player, body.level(), pos);
                if (added <= 0.0F) {
                    finish(ActionOutcome.REJECTED, "this body cannot break that block");
                    return;
                }
                progress[0] += added;
                player.getMainHandItem().mineBlock(body.level(), current, pos, player);
                body.level().destroyBlockProgress(body.entity().getId(), pos, (int) (progress[0] * 10.0F));
                if (progress[0] >= 1.0F) {
                    boolean broken = body.level().destroyBlock(pos, true, body.entity());
                    finish(broken ? ActionOutcome.DONE : ActionOutcome.NO_EFFECT,
                            broken
                                    ? "broke " + blockId + " at " + dimension + " " + pos.getX() + " "
                                            + pos.getY() + " " + pos.getZ()
                                    : "the block did not break");
                    return;
                }
                ticks.onNextTick(this);
            }

            private void finish(ActionOutcome outcome, String detail) {
                body.level().destroyBlockProgress(body.entity().getId(), pos, -1);
                context.commit();
                context.close();
                result.complete(new ActionResult(outcome, detail));
            }
        };
        ticks.onNextTick(step);
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
        boolean struck = victim.hurt(victim.level().damageSources().mobAttack(attacker), attackDamageOf(activeItem(body)));
        if (struck) {
            wearActive(body);
        }
        return struck
                ? ActionResult.done("struck " + id)
                : new ActionResult(ActionOutcome.NO_EFFECT, "the target was not affected");
    }

    /** The item in the body's active slot, or empty when nothing is equipped. */
    private ItemStack activeItem(BodyRegistry.BodyState body) {
        int slot = bodies.activeSlot(body.id().toString());
        if (slot < 0 || body.inventory() == null) {
            return ItemStack.EMPTY;
        }
        return body.inventory().getItem(slot);
    }

    /** Base fist damage plus the main-hand attack modifiers of the equipped item. */
    private static float attackDamageOf(ItemStack weapon) {
        if (weapon.isEmpty()) {
            return BASE_ATTACK_DAMAGE;
        }
        float[] bonus = {0.0F};
        weapon.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.value() == Attributes.ATTACK_DAMAGE.value()) {
                bonus[0] += (float) modifier.amount();
            }
        });
        return BASE_ATTACK_DAMAGE + bonus[0];
    }

    /** Wears the equipped item by one point; a carrier without a living body simply has nothing to wear. */
    private void wearActive(BodyRegistry.BodyState body) {
        if (!(body.entity() instanceof LivingEntity living)) {
            return;
        }
        ItemStack weapon = activeItem(body);
        if (!weapon.isEmpty()) {
            weapon.hurtAndBreak(1, living, EquipmentSlot.MAINHAND);
        }
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
