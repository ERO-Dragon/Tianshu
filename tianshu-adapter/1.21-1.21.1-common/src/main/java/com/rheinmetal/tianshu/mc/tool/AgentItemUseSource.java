package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentItemUsePort;
import com.rheinmetal.tianshu.api.agent.BlockFace;
import com.rheinmetal.tianshu.api.agent.BlockTarget;
import com.rheinmetal.tianshu.api.agent.ItemUseTarget;
import com.rheinmetal.tianshu.api.assembly.ServerTickPort;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.mc.body.ActionContext;
import com.rheinmetal.tianshu.mc.body.ActionPlayerFactory;
import com.rheinmetal.tianshu.mc.body.BodyRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uses the item in the body's active slot through the action player: {@code useOn} for a block face, {@code use}
 * for the air/self path, and a bounded held-use session for items that start one.
 *
 * <p>The session mirrors vanilla's shape without ticking the facade player: it calls {@code onUseTick} each server
 * tick, then finishes the item at the end of its duration or releases it at the requested hold. A shield has no
 * finish or release effect, so a held session on it reports {@code NO_EFFECT} rather than pretending.</p>
 */
public final class AgentItemUseSource implements AgentItemUsePort {

    private static final double REACH_SQUARED = 36.0;
    private static final int BOW_LIKE_DURATION = 72_000;
    private static final int DEFAULT_BOW_HOLD_TICKS = 20;
    private static final int SESSION_TIMEOUT_TICKS = 400;

    private final MinecraftServer server;
    private final BodyRegistry bodies;
    private final ActionPlayerFactory actionPlayers;

    public AgentItemUseSource(MinecraftServer server, BodyRegistry bodies, ActionPlayerFactory actionPlayers) {
        this.server = server;
        this.bodies = bodies;
        this.actionPlayers = actionPlayers;
    }

    @Override
    public CompletableFuture<ActionResult> use(ActorRef actor, ItemUseTarget target, int holdTicks) {
        CompletableFuture<ActionResult> result = new CompletableFuture<>();
        server.execute(() -> start(actor, target, Math.max(0, holdTicks), result));
        return result;
    }

    private void start(ActorRef actor, ItemUseTarget target, int holdTicks, CompletableFuture<ActionResult> result) {
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null) {
            result.complete(ActionResult.rejected("the agent body is not present"));
            return;
        }
        ServerLevel level = body.level();
        int slot = bodies.activeSlot(body.id().toString());
        if (slot < 0 || body.inventory() == null || body.inventory().getItem(slot).isEmpty()) {
            result.complete(ActionResult.rejected("nothing is equipped in the active slot"));
            return;
        }
        BlockTarget block = null;
        BlockFace face = null;
        if (target instanceof ItemUseTarget.Block blockTarget) {
            block = blockTarget.block();
            face = blockTarget.face();
            if (!level.dimension().location().toString().equals(block.dimension())) {
                result.complete(ActionResult.rejected("the target is in another dimension"));
                return;
            }
            BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
            if (!level.hasChunkAt(pos)) {
                result.complete(ActionResult.rejected("that chunk is not loaded"));
                return;
            }
            if (!(distanceSquared(body.position(), pos) <= REACH_SQUARED)) {
                result.complete(ActionResult.rejected("the block is out of reach"));
                return;
            }
        }
        ActionContext context = ActionContext.hand(server, actor, bodies, actionPlayers, slot).orElse(null);
        if (context == null) {
            result.complete(ActionResult.rejected("the agent body is not present"));
            return;
        }
        Player player = context.player();
        ItemStack hand = player.getMainHandItem();
        String itemId = itemId(hand);
        if (block != null) {
            BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
            Direction direction = Faces.of(face, player.position(), pos);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), direction, pos, false);
            InteractionResult outcome = hand.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            context.commit();
            context.close();
            result.complete(outcome.consumesAction()
                    ? ActionResult.done("used " + itemId)
                    : new ActionResult(ActionOutcome.NO_EFFECT, "nothing happened"));
            return;
        }
        InteractionResultHolder<ItemStack> used = hand.use(level, player, InteractionHand.MAIN_HAND);
        player.getInventory().setItem(0, used.getObject());
        if (!player.isUsingItem()) {
            context.commit();
            context.close();
            result.complete(used.getResult().consumesAction()
                    ? ActionResult.done("used " + itemId)
                    : new ActionResult(ActionOutcome.NO_EFFECT, "nothing happened"));
            return;
        }
        startHeldUse(level, player, context, itemId, holdTicks, result, actor.id());
    }

    private void startHeldUse(ServerLevel level, Player player, ActionContext context, String itemId,
                              int holdTicks, CompletableFuture<ActionResult> result, String actorId) {
        ServerTickPort ticks = TianshuRuntimePorts.find(ServerTickPort.class).orElse(null);
        if (ticks == null) {
            player.stopUsingItem();
            context.close();
            result.complete(ActionResult.rejected("the server does not offer a tick source"));
            return;
        }
        ItemStack using = player.getUseItem();
        int duration = using.getUseDuration(player);
        if (duration <= 0) {
            player.stopUsingItem();
            context.close();
            result.complete(ActionResult.rejected("this item has no usable duration"));
            return;
        }
        int hold = holdTicks > 0
                ? Math.min(holdTicks, duration)
                : (duration >= BOW_LIKE_DURATION ? DEFAULT_BOW_HOLD_TICKS : duration);
        AtomicInteger remaining = new AtomicInteger(duration);
        AtomicInteger elapsed = new AtomicInteger();
        Runnable step = new Runnable() {
            @Override
            public void run() {
                if (result.isDone()) {
                    return;
                }
                if (com.rheinmetal.tianshu.client.tool.BodyOperations.cancelled(actorId)) {
                    player.stopUsingItem();
                    finish(ActionOutcome.REJECTED, "the call was cancelled");
                    return;
                }
                if (!player.isUsingItem()) {
                    finish(ActionOutcome.NO_EFFECT, "the use ended early");
                    return;
                }
                player.getUseItem().onUseTick(level, player, remaining.get());
                remaining.decrementAndGet();
                elapsed.incrementAndGet();
                if (remaining.get() <= 0) {
                    ItemStack after = player.getUseItem().finishUsingItem(level, player);
                    player.getInventory().setItem(0, after);
                    player.stopUsingItem();
                    finish(after.isEmpty() || !ItemStack.matches(after, using)
                            ? ActionOutcome.DONE : ActionOutcome.NO_EFFECT, "finished " + itemId);
                    return;
                }
                if (elapsed.get() >= hold) {
                    player.getUseItem().releaseUsing(level, player, remaining.get());
                    player.stopUsingItem();
                    finish(ActionOutcome.DONE, "released " + itemId);
                    return;
                }
                if (elapsed.get() >= SESSION_TIMEOUT_TICKS) {
                    player.stopUsingItem();
                    finish(ActionOutcome.REJECTED, "the use timed out");
                    return;
                }
                ticks.onNextTick(this);
            }

            private void finish(ActionOutcome outcome, String detail) {
                context.commit();
                context.close();
                result.complete(new ActionResult(outcome, detail));
            }
        };
        ticks.onNextTick(step);
    }

    private static String itemId(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    private static double distanceSquared(Vec3 from, BlockPos pos) {
        double dx = pos.getX() + 0.5D - from.x;
        double dy = pos.getY() + 0.5D - from.y;
        double dz = pos.getZ() + 0.5D - from.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
