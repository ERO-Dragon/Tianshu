package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentItemUsePort;
import com.rheinmetal.tianshu.api.agent.BlockFace;
import com.rheinmetal.tianshu.api.agent.BlockTarget;
import com.rheinmetal.tianshu.api.agent.ItemUseTarget;
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

/**
 * Uses the item in the body's active slot through the action player: {@code useOn} for a block face, {@code use}
 * for the air/self path.
 *
 * <p>Instant uses only. If the item starts a sustained use (food, bow, shield), the platform stops it and says so
 * instead of pretending the item was consumed — a held use needs its own tick session, which does not exist yet.</p>
 */
public final class AgentItemUseSource implements AgentItemUsePort {

    private static final double REACH_SQUARED = 36.0;

    private final MinecraftServer server;
    private final BodyRegistry bodies;
    private final ActionPlayerFactory actionPlayers;

    public AgentItemUseSource(MinecraftServer server, BodyRegistry bodies, ActionPlayerFactory actionPlayers) {
        this.server = server;
        this.bodies = bodies;
        this.actionPlayers = actionPlayers;
    }

    @Override
    public CompletableFuture<ActionResult> use(ActorRef actor, ItemUseTarget target) {
        CompletableFuture<ActionResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(useNow(actor, target)));
        return result;
    }

    private ActionResult useNow(ActorRef actor, ItemUseTarget target) {
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null) {
            return ActionResult.rejected("the agent body is not present");
        }
        ServerLevel level = body.level();
        int slot = bodies.activeSlot(body.id().toString());
        if (slot < 0 || body.inventory() == null || body.inventory().getItem(slot).isEmpty()) {
            return ActionResult.rejected("nothing is equipped in the active slot");
        }
        BlockTarget block = null;
        BlockFace face = null;
        if (target instanceof ItemUseTarget.Block blockTarget) {
            block = blockTarget.block();
            face = blockTarget.face();
            if (!level.dimension().location().toString().equals(block.dimension())) {
                return ActionResult.rejected("the target is in another dimension");
            }
            BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
            if (!level.hasChunkAt(pos)) {
                return ActionResult.rejected("that chunk is not loaded");
            }
            if (distanceSquared(body.position(), pos) > REACH_SQUARED) {
                return ActionResult.rejected("the block is out of reach");
            }
        }
        ActionContext context = ActionContext.hand(server, actor, bodies, actionPlayers, slot).orElse(null);
        if (context == null) {
            return ActionResult.rejected("the agent body is not present");
        }
        Player player = context.player();
        ItemStack stack = player.getMainHandItem();
        ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String itemId = itemKey == null ? "" : itemKey.toString();
        InteractionResult outcome;
        try {
            if (block == null) {
                InteractionResultHolder<ItemStack> used = stack.use(level, player, InteractionHand.MAIN_HAND);
                outcome = used.getResult();
                player.getInventory().setItem(0, used.getObject());
            } else {
                BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
                Direction direction = Faces.of(face, player.position(), pos);
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), direction, pos, false);
                outcome = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            }
        } finally {
            context.commit();
            context.close();
        }
        if (player.isUsingItem()) {
            player.stopUsingItem();
            return ActionResult.rejected("this item needs a sustained use, which is not supported yet");
        }
        if (outcome.consumesAction()) {
            return ActionResult.done("used " + itemId);
        }
        return new ActionResult(ActionOutcome.NO_EFFECT, "nothing happened");
    }

    private static double distanceSquared(Vec3 from, BlockPos pos) {
        double dx = pos.getX() + 0.5D - from.x;
        double dy = pos.getY() + 0.5D - from.y;
        double dz = pos.getZ() + 0.5D - from.z;
        return dx * dx + dy * dy + dz * dz;
    }
}